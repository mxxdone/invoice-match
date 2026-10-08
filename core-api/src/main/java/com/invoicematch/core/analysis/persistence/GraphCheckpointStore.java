package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.GraphRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SDK checkpoint and versioned pending-write persistence.
 * Callers validate ownership and hold case -> graph locks in their transaction.
 * Inserts retain the database triggers that account for stored bytes and write counts.
 */
@Repository
public class GraphCheckpointStore {
    private final JdbcTemplate jdbc;
    public GraphCheckpointStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Checkpoint(UUID id, UUID parentId, String hash, String envelope, long sequence) {}
    public record Write(UUID checkpointId, UUID taskId, int index, int version, String previousHash,
            String channel, String taskPath, String hash, String payload) {}

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
