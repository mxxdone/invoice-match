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
        jdbc.update("update graph_run set status=?,error_code=?,execution_token=null,lease_until=null,updated_at=clock_timestamp() where id=?",status,error,id);
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
    private static GraphRun run(java.sql.ResultSet rs) throws java.sql.SQLException {
        var until=rs.getTimestamp("lease_until");
        return new GraphRun(rs.getObject("id",UUID.class),rs.getObject("invoice_case_id",UUID.class),rs.getLong("case_version"),
                rs.getString("context"),rs.getString("context_hash"),rs.getString("status"),rs.getString("active_segment"),
                rs.getInt("start_attempts"),rs.getInt("resume_attempts"),rs.getObject("execution_token",UUID.class),
                until==null?null:until.toInstant(),rs.getBoolean("lease_active"),rs.getInt("checkpoint_count"),rs.getInt("write_count"),rs.getInt("stored_bytes"));
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
