package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.GraphRun;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All graph SQL and lock order (case -> graph -> checkpoint/interrupt) live here. */
@Repository
public class GraphStore {
    private final JdbcTemplate jdbc;
    public GraphStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Checkpoint(UUID id, UUID parentId, String hash, String envelope, long sequence) {}
    public record Write(UUID checkpointId, UUID taskId, int index, int version, String previousHash,
            String channel, String taskPath, String hash, String payload) {}
    public record Waiting(String interruptId, UUID checkpointId, String checkpointHash, UUID taskId,
            int writeVersion, String writeHash, int reviewVersion, UUID token) {}

    public Optional<GraphRun> existing(UUID caseId, String hash) {
        return jdbc.query("select *,lease_until>clock_timestamp() lease_active from graph_run where invoice_case_id=? and context_hash=?",
                (rs,n)->run(rs), caseId, hash).stream().findFirst();
    }
    /** Latest committed projection of one run, normally read while the caller already holds its lock. */
    public Optional<GraphRun> read(UUID id) {
        return jdbc.query("select *,lease_until>clock_timestamp() lease_active from graph_run where id=?",
                (rs,n)->run(rs),id).stream().findFirst();
    }
    /** Unlocked newest-first discovery: bounded IDs only; lock each id before trusting its state. */
    public List<UUID> recent(UUID caseId) {
        return jdbc.query("select id from graph_run where invoice_case_id=? order by created_at desc,id desc limit 20",
                (rs,n)->rs.getObject(1,UUID.class),caseId);
    }
    public List<UUID> lockCaseRuns(UUID caseId) {
        return jdbc.query("select id from graph_run where invoice_case_id=? and status<>'STALE' order by id for update",
                (rs,n)->rs.getObject(1,UUID.class),caseId);
    }
    public boolean otherInput(UUID caseId,String hash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from graph_run where invoice_case_id=? and checkpoint_schema=4 and context_hash<>?)",Boolean.class,caseId,hash));
    }
    public Optional<UUID> predecessor(UUID id) {
        return jdbc.query("select predecessor_id from graph_successor where run_id=?",(rs,n)->rs.getObject(1,UUID.class),id).stream().findFirst();
    }
    public void successor(UUID id,UUID parent,UUID caseId) {
        jdbc.update("insert into graph_successor(run_id,predecessor_id,invoice_case_id) values(?,?,?)",id,parent,caseId);
    }
    public void insert(UUID id, UUID caseId, long version, ProposalStore.Source source, String context,
            String hash, BigDecimal costCeiling) {
        jdbc.update("""
            insert into graph_run(id,invoice_case_id,case_version,evidence_bundle_id,parser_run_id,match_result_id,
                context,context_hash,workflow_version,graph_version,serializer_version,checkpoint_schema,status,cost_ceiling)
            values(?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,'QUEUED',?)
            """,id,caseId,version,source.bundleId(),source.parserRunId(),source.matchId(),context,hash,
                GraphRun.WORKFLOW,GraphRun.GRAPH,GraphRun.SERIALIZER,GraphRun.SCHEMA,costCeiling);
    }
    public Optional<GraphRun> lock(UUID id) {
        var caseId = jdbc.query("select invoice_case_id from graph_run where id=?",(rs,n)->rs.getObject(1,UUID.class),id).stream().findFirst();
        if (caseId.isEmpty()) return Optional.empty();
        jdbc.queryForList("select id from invoice_case where id=? for update",caseId.get());
        return jdbc.query("select *,lease_until>clock_timestamp() lease_active from graph_run where id=? for update",
                (rs,n)->run(rs),id).stream().findFirst();
    }
    public boolean current(GraphRun run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from invoice_case c join purchase_order_snapshot p on p.purchase_order_id=c.purchase_order_id
                join graph_run g on g.invoice_case_id=c.id where g.id=? and c.version=g.case_version
                and c.status in ('SUBMITTED','REVIEW_PENDING')
                and g.evidence_bundle_id=(select id from evidence_bundle where invoice_case_id=c.id order by version_number desc limit 1)
                and g.match_result_id=(select id from match_result where invoice_case_id=c.id order by result_number desc limit 1)
                and p.payload_hash=g.context->'matchResult'->'purchasingSnapshot'->>'payloadHash')
            """,Boolean.class,run.id()));
    }
    public Instant claim(UUID id, UUID token, Duration lease) {
        return jdbc.queryForObject("""
            update graph_run set status='RUNNING',execution_token=?,lease_until=clock_timestamp()+ (? * interval '1 millisecond'),
                start_attempts=start_attempts+case when active_segment='START' then 1 else 0 end,
                resume_attempts=resume_attempts+case when active_segment='RESUME' then 1 else 0 end,
                updated_at=clock_timestamp() where id=? returning lease_until
            """,(rs,n)->rs.getTimestamp(1).toInstant(),token,lease.toMillis(),id);
    }
    public Instant heartbeat(UUID id, UUID token, Duration lease) {
        return jdbc.queryForObject("""
            update graph_run set lease_until=greatest(lease_until,clock_timestamp()+ (? * interval '1 millisecond')),
                updated_at=clock_timestamp() where id=? and execution_token=? and lease_until>clock_timestamp() returning lease_until
            """,(rs,n)->rs.getTimestamp(1).toInstant(),lease.toMillis(),id,token);
    }
    public boolean owned(UUID id, UUID token) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from graph_run where id=? and status='RUNNING' and execution_token=? and lease_until>clock_timestamp())",
                Boolean.class,id,token));
    }
    public void terminal(UUID id, String status, String error) {
        jdbc.update("update graph_run set status=?,error_code=?,execution_token=null,lease_until=null,updated_at=clock_timestamp()"
                + " where id=? and status not in ('STALE')",status,error,id);
    }
    public Optional<Checkpoint> checkpoint(UUID id, UUID checkpointId) {
        return jdbc.query("select * from graph_checkpoint where run_id=? and checkpoint_id=?",
                (rs,n)->checkpoint(rs),id,checkpointId).stream().findFirst();
    }
    public Optional<Checkpoint> latest(UUID id) {
        return jdbc.query("select * from graph_checkpoint where run_id=? order by sequence_number desc limit 1",
                (rs,n)->checkpoint(rs),id).stream().findFirst();
    }
    public void checkpoint(UUID id, UUID checkpointId, UUID parentId, String envelope, String hash, UUID token) {
        jdbc.update("insert into graph_checkpoint(run_id,checkpoint_id,graph_version,parent_id,envelope,payload_hash,execution_token) values(?,?,?,?,cast(? as jsonb),?,?)",
                id,checkpointId,GraphRun.GRAPH,parentId,envelope,hash,token);
    }
    public Optional<Write> write(UUID id, UUID checkpointId, UUID taskId, int index, int version) {
        return jdbc.query("select * from graph_pending_write where run_id=? and checkpoint_id=? and task_id=? and write_index=? and version_number=?",
                (rs,n)->write(rs),id,checkpointId,taskId,index,version).stream().findFirst();
    }
    public List<Write> writes(UUID id, UUID checkpointId) {
        return jdbc.query("""
            select distinct on(task_id,write_index) * from graph_pending_write where run_id=? and checkpoint_id=?
                order by task_id,write_index,version_number desc
            """,(rs,n)->write(rs),id,checkpointId);
    }
    public void write(UUID id, Write write, UUID token) {
        jdbc.update("""
            insert into graph_pending_write(run_id,checkpoint_id,task_id,write_index,version_number,previous_version,
                previous_hash,channel,task_path,payload,payload_hash,execution_token)
            values(?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?)
            """,id,write.checkpointId(),write.taskId(),write.index(),write.version(),write.version()==1?null:write.version()-1,
                write.previousHash(),write.channel(),write.taskPath(),write.payload(),write.hash(),token);
    }
    public Optional<Waiting> waiting(UUID id) {
        return jdbc.query("select * from graph_interrupt where run_id=?",(rs,n)->new Waiting(rs.getString("interrupt_id"),
                rs.getObject("checkpoint_id",UUID.class),rs.getString("checkpoint_hash"),rs.getObject("task_id",UUID.class),
                rs.getInt("write_version"),rs.getString("write_hash"),rs.getInt("review_version"),rs.getObject("execution_token",UUID.class)),id).stream().findFirst();
    }
    public void wait(UUID id, Waiting wait) {
        jdbc.update("""
            insert into graph_interrupt(run_id,interrupt_id,checkpoint_id,checkpoint_hash,task_id,write_index,
                write_version,write_hash,review_version,execution_token) values(?,?,?,?,?,-3,?,?,?,?)
            """,id,wait.interruptId(),wait.checkpointId(),wait.checkpointHash(),wait.taskId(),wait.writeVersion(),
                wait.writeHash(),wait.reviewVersion(),wait.token());
        jdbc.update("update graph_run set status='WAITING_HUMAN',waiting_interrupt_id=?,execution_token=null,lease_until=null,updated_at=clock_timestamp() where id=?",
                wait.interruptId(),id);
    }
    public int jsonBytes(String value) { return jdbc.queryForObject("select octet_length(cast(? as jsonb)::text)",Integer.class,value); }
    public void review(UUID id,Waiting wait,UUID reviewId,UUID eventId,String actor,String requestId,String confirmation,String hash,String reason) {
        jdbc.update("""
            insert into graph_review(id,run_id,interrupt_id,checkpoint_id,checkpoint_hash,review_version,resume_event_id,
                actor,request_id,confirmation,confirmation_hash,reason)
            values(?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?)
            """,reviewId,id,wait.interruptId(),wait.checkpointId(),wait.checkpointHash(),wait.reviewVersion(),eventId,actor,requestId,confirmation,hash,reason);
    }
    public void resumeEvent(UUID eventId,UUID id,UUID reviewId,Waiting wait,String payload) {
        jdbc.update("""
            insert into graph_resume_outbox(id,run_id,review_id,interrupt_id,checkpoint_id,checkpoint_hash,review_version,payload)
            values(?,?,?,?,?,?,?,cast(? as jsonb))
            """,eventId,id,reviewId,wait.interruptId(),wait.checkpointId(),wait.checkpointHash(),wait.reviewVersion(),payload);
    }
    public void queueResume(UUID id) {
        jdbc.update("update graph_run set status='QUEUED',active_segment='RESUME',updated_at=clock_timestamp() where id=?",id);
    }
    public record Review(UUID id,String confirmation,String hash,String actor,String reason,Instant createdAt) {}
    public Optional<Review> review(UUID id) {
        return jdbc.query("select * from graph_review where run_id=?",(rs,n)->new Review(rs.getObject("id",UUID.class),
            rs.getString("confirmation"),rs.getString("confirmation_hash"),rs.getString("actor"),rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant()),id).stream().findFirst();
    }
    public boolean reviewConsumed(UUID id,UUID review) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from graph_resume_consumption where run_id=? and review_id=?)",Boolean.class,id,review));
    }
    /** Validation input only: does not create or mutate any v1 execution. */
    public com.invoicematch.core.analysis.domain.ProposalRun advisoryInput(UUID id) {
        return jdbc.queryForObject("select *,lease_until>clock_timestamp() lease_active from graph_run where id=?",(rs,n)->
            new com.invoicematch.core.analysis.domain.ProposalRun(id,rs.getObject("invoice_case_id",UUID.class),
                rs.getObject("evidence_bundle_id",UUID.class),rs.getObject("parser_run_id",UUID.class),rs.getObject("match_result_id",UUID.class),
                rs.getString("context_hash"),rs.getString("context"),rs.getString("status"),rs.getObject("execution_token",UUID.class),
                rs.getTimestamp("lease_until")==null?null:rs.getTimestamp("lease_until").toInstant(),
                rs.getInt("start_attempts")+rs.getInt("resume_attempts"),rs.getInt("reserved_calls"),rs.getInt("reserved_tokens"),
                rs.getInt("tool_calls"),rs.getBoolean("lease_active"),true,rs.getString("error_code")),id);
    }
    public boolean supported(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select checkpoint_schema=? from graph_run where id=?",Boolean.class,GraphRun.SCHEMA,id));
    }
    public record Stage(UUID id,String stage,String hash,String payload) {}
    public List<Stage> stages(UUID id) {
        return jdbc.query("select * from graph_stage where run_id=? order by stage",(rs,n)->
            new Stage(rs.getObject("id",UUID.class),rs.getString("stage"),rs.getString("payload_hash"),rs.getString("payload")),id);
    }
    public List<ProposalStore.Step> validationSteps(UUID id) {
        return stages(id).stream().map(s->new ProposalStore.Step(s.stage(),s.hash(),s.payload())).toList();
    }
    public UUID stage(UUID id,String stage,String payload,String hash,UUID token) {
        UUID ref=UUID.randomUUID();jdbc.update("insert into graph_stage(id,run_id,stage,payload,payload_hash,execution_token) values(?,?,?,cast(? as jsonb),?,?)",
            ref,id,stage,payload,hash,token);return ref;
    }
    public Optional<Integer> reservation(UUID id,UUID requestId) {
        return jdbc.query("select reserved_tokens from graph_call_reservation where run_id=? and request_id=?",(rs,n)->rs.getInt(1),id,requestId).stream().findFirst();
    }
    public BigDecimal costCeiling(UUID id) { return jdbc.queryForObject("select cost_ceiling from graph_run where id=?",BigDecimal.class,id); }
    public BigDecimal reservedCost(UUID id) { return jdbc.queryForObject("select reserved_cost from graph_run where id=?",BigDecimal.class,id); }
    public void reserve(UUID id,UUID requestId,int tokens,BigDecimal cost,UUID token) {
        jdbc.update("insert into graph_call_reservation(run_id,request_id,reserved_tokens,reserved_cost,execution_token) values(?,?,?,?,?)",id,requestId,tokens,cost,token);
    }
    public record Result(String hash,String payload) {}
    public Optional<Result> result(UUID id) {
        return jdbc.query("select payload_hash,payload::text from graph_proposal where run_id=?",(rs,n)->new Result(rs.getString(1),rs.getString(2)),id).stream().findFirst();
    }
    public void complete(UUID id,String payload,String hash,UUID token) {
        jdbc.update("insert into graph_proposal(run_id,payload,payload_hash,execution_token) values(?,cast(? as jsonb),?,?)",id,payload,hash,token);
        terminal(id,"COMPLETED",null);
    }
    private static GraphRun run(java.sql.ResultSet rs) throws java.sql.SQLException {
        var until=rs.getTimestamp("lease_until");
        return new GraphRun(rs.getObject("id",UUID.class),rs.getObject("invoice_case_id",UUID.class),rs.getLong("case_version"),
                rs.getString("context"),rs.getString("context_hash"),rs.getString("status"),rs.getString("active_segment"),
                rs.getInt("start_attempts"),rs.getInt("resume_attempts"),rs.getInt("reserved_calls"),rs.getInt("reserved_tokens"),
                rs.getInt("tool_calls"),rs.getObject("execution_token",UUID.class),
                until==null?null:until.toInstant(),rs.getBoolean("lease_active"),rs.getInt("checkpoint_count"),rs.getInt("write_count"),rs.getInt("stored_bytes"),
                rs.getInt("checkpoint_schema"),rs.getString("error_code"),rs.getTimestamp("created_at").toInstant());
    }
    private static Checkpoint checkpoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Checkpoint(rs.getObject("checkpoint_id",UUID.class),rs.getObject("parent_id",UUID.class),
                rs.getString("payload_hash"),rs.getString("envelope"),rs.getLong("sequence_number"));
    }
    private static Write write(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Write(rs.getObject("checkpoint_id",UUID.class),rs.getObject("task_id",UUID.class),rs.getInt("write_index"),
                rs.getInt("version_number"),rs.getString("previous_hash"),rs.getString("channel"),rs.getString("task_path"),
                rs.getString("payload_hash"),rs.getString("payload"));
    }
}
