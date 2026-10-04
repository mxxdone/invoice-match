package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.ProposalRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Local reads, lock order and conditional persistence. No authorization or SDK calls. */
@Repository
public class ProposalStore {
    private final JdbcTemplate jdbc;
    public ProposalStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record CaseState(long version, String status) {}
    public record Source(UUID bundleId, UUID parserRunId, UUID matchId, String bundleHash,
            String bundlePayload, String matchHash, String matchPayload, String purchasingHash,
            Instant submittedAt) {}
    public record Parsed(UUID documentId, String checksum, String payloadHash, String payload) {}
    public record Item(String itemId, String itemName, String purchaseOrderLineId) {}
    public record Step(String stage, String hash, String payload) {}
    public record Saved(UUID id, UUID caseId, UUID bundleId, UUID matchId, String contextHash,
            String payloadHash, String payload) {}
    public record Event(UUID id, UUID token, String payload) {}

    public Optional<CaseState> lockCase(UUID caseId) {
        return jdbc.query("select version,status from invoice_case where id=? for update",
                (rs,n)->new CaseState(rs.getLong(1),rs.getString(2)),caseId).stream().findFirst();
    }
    public Optional<Source> source(UUID caseId) {
        return jdbc.query("""
            select b.id bundle_id,a.id parser_id,m.id match_id,b.payload_hash bundle_hash,
                b.payload::text bundle_payload,m.result_hash match_hash,m.payload::text match_payload,
                p.payload_hash purchasing_hash,b.submitted_at
            from invoice_case c
            join lateral (select * from evidence_bundle where invoice_case_id=c.id
                order by version_number desc limit 1) b on true
            join analysis_run a on a.invoice_case_id=c.id and a.evidence_bundle_id=b.id
                and a.workflow_version='document-parser-v1' and a.status='COMPLETED'
            join lateral (select * from match_result where invoice_case_id=c.id
                order by result_number desc limit 1) m on m.evidence_bundle_id=b.id
            join purchase_order_snapshot p on p.purchase_order_id=c.purchase_order_id
            where c.id=?
            """,(rs,n)->new Source(rs.getObject("bundle_id",UUID.class),rs.getObject("parser_id",UUID.class),
                    rs.getObject("match_id",UUID.class),rs.getString("bundle_hash"),rs.getString("bundle_payload"),
                    rs.getString("match_hash"),rs.getString("match_payload"),rs.getString("purchasing_hash"),
                    rs.getTimestamp("submitted_at").toInstant()),caseId).stream().findFirst();
    }
    public List<Parsed> parsed(UUID parserRunId) {
        return jdbc.query("select document_id,source_checksum,payload_hash,payload::text from analysis_document_result"
                + " where run_id=? and outcome='SUCCESS' order by document_id",(rs,n)->new Parsed(
                rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),parserRunId);
    }
    public List<Item> items(UUID caseId) {
        return jdbc.query("select l.item_id,l.item_name,l.purchase_order_line_id from invoice_case c"
                + " join purchase_order_line_snapshot l on l.purchase_order_id=c.purchase_order_id"
                + " where c.id=? and l.active order by l.purchase_order_line_id limit 101",
                (rs,n)->new Item(rs.getString(1),rs.getString(2),rs.getString(3)),caseId);
    }
    public Optional<UUID> existing(UUID parser, UUID match) {
        return jdbc.query("select id from proposal_run where parser_run_id=? and match_result_id=?"
                + " and workflow_version='ai-review-v1'",(rs,n)->rs.getObject(1,UUID.class),parser,match).stream().findFirst();
    }
    public void insert(UUID id, UUID caseId, Source source, String context, String hash, String event) {
        jdbc.update("insert into proposal_run(id,invoice_case_id,evidence_bundle_id,parser_run_id,match_result_id,"
                + " context_hash,context,workflow_version,status) values(?,?,?,?,?,?,cast(? as jsonb),'ai-review-v1','QUEUED')",
                id,caseId,source.bundleId(),source.parserRunId(),source.matchId(),hash,context);
        jdbc.update("insert into proposal_request_outbox(id,payload,status) values(?,cast(? as jsonb),'READY')",id,event);
    }
    public Optional<ProposalRun> read(UUID id) {
        return jdbc.query("select *,lease_until>clock_timestamp() lease_active,"
                + " next_attempt_at<=clock_timestamp() due from proposal_run where id=?",
                (rs,n)->run(rs),id).stream().findFirst();
    }
    public Optional<ProposalRun> lock(UUID id) {
        var caseId=jdbc.query("select invoice_case_id from proposal_run where id=?",
                (rs,n)->rs.getObject(1,UUID.class),id).stream().findFirst();
        if(caseId.isEmpty()) return Optional.empty();
        lockCase(caseId.get());
        return jdbc.query("select *,lease_until>clock_timestamp() lease_active,"
                + " next_attempt_at<=clock_timestamp() due from proposal_run where id=? for update",
                (rs,n)->run(rs),id).stream().findFirst();
    }
    public boolean current(ProposalRun run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from invoice_case c
                join purchase_order_snapshot p on p.purchase_order_id=c.purchase_order_id
                where c.id=? and c.status in ('SUBMITTED','REVIEW_PENDING')
                and ?=(select id from evidence_bundle where invoice_case_id=c.id order by version_number desc limit 1)
                and ?=(select id from match_result where invoice_case_id=c.id order by result_number desc limit 1)
                and p.payload_hash=(select context->'matchResult'->'purchasingSnapshot'->>'payloadHash'
                    from proposal_run where id=?))
            """,Boolean.class,run.caseId(),run.bundleId(),run.matchResultId(),run.id()));
    }
    public void stale(UUID id) {
        jdbc.update("update proposal_run set status='STALE',execution_token=null,lease_until=null,"
                + " updated_at=clock_timestamp() where id=? and status<>'STALE'",id);
        jdbc.update("update proposal_request_outbox set status='CANCELLED',lease_token=null,lease_until=null"
                + " where id=? and status in ('READY','CLAIMED')",id);
    }
    public Optional<Instant> claim(UUID id, UUID token, Duration lease) {
        return jdbc.query("update proposal_run set status='RUNNING',execution_token=?,lease_until=clock_timestamp()"
                + " + (? * interval '1 millisecond'),execution_attempt=execution_attempt+1,updated_at=clock_timestamp()"
                + " where id=? and execution_attempt<3 and next_attempt_at<=clock_timestamp()"
                + " and (status='QUEUED' or (status='RUNNING' and lease_until<=clock_timestamp())) returning lease_until",
                (rs,n)->rs.getTimestamp(1).toInstant(),token,lease.toMillis(),id).stream().findFirst();
    }
    public Optional<Instant> heartbeat(UUID id, UUID token, Duration lease) {
        return jdbc.query("update proposal_run set lease_until=greatest(lease_until,clock_timestamp()+"
                + " (? * interval '1 millisecond')),updated_at=clock_timestamp() where id=? and status='RUNNING'"
                + " and execution_token=? and lease_until>clock_timestamp() returning lease_until",
                (rs,n)->rs.getTimestamp(1).toInstant(),lease.toMillis(),id,token).stream().findFirst();
    }
    public List<Step> steps(UUID id) {
        return jdbc.query("select stage,payload_hash,payload::text from proposal_step where run_id=? order by stage",
                (rs,n)->new Step(rs.getString(1),rs.getString(2),rs.getString(3)),id);
    }
    public void step(UUID id, String stage, String payload, String hash) {
        jdbc.update("insert into proposal_step(run_id,stage,payload,payload_hash) values(?,?,cast(? as jsonb),?)",
                id,stage,payload,hash);
    }
    public Optional<Integer> reservation(UUID id, UUID requestId) {
        return jdbc.query("select reserved_tokens from proposal_call_reservation where run_id=? and request_id=?",
                (rs,n)->rs.getInt(1),id,requestId).stream().findFirst();
    }
    public void reserveCall(UUID id, UUID requestId, int tokens) {
        jdbc.update("update proposal_run set reserved_calls=reserved_calls+1,reserved_tokens=reserved_tokens+?"
                + " where id=?",tokens,id);
        jdbc.update("insert into proposal_call_reservation(run_id,request_id,reserved_tokens) values(?,?,?)",id,requestId,tokens);
    }
    public void countTool(UUID id) { jdbc.update("update proposal_run set tool_calls=tool_calls+1 where id=?",id); }
    public Optional<Saved> saved(UUID id) {
        return jdbc.query("select * from proposal where id=?",(rs,n)->new Saved(rs.getObject("id",UUID.class),
                rs.getObject("invoice_case_id",UUID.class),rs.getObject("evidence_bundle_id",UUID.class),
                rs.getObject("match_result_id",UUID.class),rs.getString("context_hash"),rs.getString("payload_hash"),
                rs.getString("payload")),id).stream().findFirst();
    }
    public void save(ProposalRun run, String payload, String hash) {
        jdbc.update("insert into proposal(id,invoice_case_id,evidence_bundle_id,match_result_id,context_hash,payload,payload_hash)"
                + " values(?,?,?,?,?,cast(? as jsonb),?)",run.id(),run.caseId(),run.bundleId(),run.matchResultId(),
                run.contextHash(),payload,hash);
        jdbc.update("update proposal_run set status='COMPLETED',execution_token=null,lease_until=null,updated_at=clock_timestamp()"
                + " where id=?",run.id());
    }
    private static ProposalRun run(ResultSet rs) throws SQLException {
        var lease=rs.getTimestamp("lease_until");
        return new ProposalRun(rs.getObject("id",UUID.class),rs.getObject("invoice_case_id",UUID.class),
                rs.getObject("evidence_bundle_id",UUID.class),rs.getObject("parser_run_id",UUID.class),
                rs.getObject("match_result_id",UUID.class),rs.getString("context_hash"),rs.getString("context"),
                rs.getString("status"),rs.getObject("execution_token",UUID.class),lease==null?null:lease.toInstant(),
                rs.getInt("execution_attempt"),rs.getInt("reserved_calls"),rs.getInt("reserved_tokens"),
                rs.getInt("tool_calls"),rs.getBoolean("lease_active"),rs.getBoolean("due"),rs.getString("error_code"));
    }
}
