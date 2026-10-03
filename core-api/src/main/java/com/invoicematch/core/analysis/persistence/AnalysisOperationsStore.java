package com.invoicematch.core.analysis.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisOperationsStore {
    private final JdbcTemplate jdbc;
    public AnalysisOperationsStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Job(UUID runId, UUID caseId, String invoiceNumber, int inputVersion, String status,
            int executionAttempt, int attemptLimit, Instant nextRetryAt, String lastErrorCode,
            String publishStatus, Instant createdAt, Instant updatedAt) {}
    public record Failure(int executionAttempt, String errorCode, String disposition, Instant createdAt,
            String publishStatus, Instant publishedAt) {}
    private static Instant instant(java.sql.ResultSet r,String name) throws java.sql.SQLException {
        var t=r.getTimestamp(name);return t==null ? null : t.toInstant();
    }
    public long count(String status) {
        return jdbc.queryForObject("select count(*) from analysis_run where (cast(? as text) is null or status=?)",Long.class,status,status);
    }
    public List<Job> jobs(String status,int offset,int size) {
        return jdbc.query("""
                select r.id,r.invoice_case_id,c.invoice_number,r.input_version,r.status,r.execution_attempt,r.attempt_limit,
                    r.created_at,r.updated_at,
                    case when r.status in ('QUEUED','RETRY_SCHEDULED') and d.status in ('READY','CLAIMED')
                        then d.next_attempt_at else null end next_attempt_at,
                    coalesce(f.error_code,result.error_code,o.last_error_code) error_code,coalesce(d.status,o.status) publish_status
                from analysis_run r join invoice_case c on c.id=r.invoice_case_id
                join analysis_request_outbox o on o.analysis_run_id=r.id
                left join lateral (select * from analysis_execution_failure where run_id=r.id
                    order by execution_attempt desc,created_at desc limit 1) f on true
                left join lateral (select * from analysis_recovery_dispatch where analysis_run_id=r.id
                    order by created_at desc,id desc limit 1) d on true
                left join lateral (select error_code from analysis_document_result where run_id=r.id and error_code is not null
                    order by created_at desc,document_id limit 1) result on true
                where (cast(? as text) is null or r.status=?) order by r.created_at desc,r.id limit ? offset ?
                """,(r,n)->new Job(r.getObject("id",UUID.class),r.getObject("invoice_case_id",UUID.class),
                r.getString("invoice_number"),r.getInt("input_version"),r.getString("status"),r.getInt("execution_attempt"),
                r.getInt("attempt_limit"),instant(r,"next_attempt_at"),r.getString("error_code"),r.getString("publish_status"),
                instant(r,"created_at"),instant(r,"updated_at")),status,status,size,offset);
    }
    public long failureCount(UUID run) {
        return jdbc.queryForObject("select count(*) from analysis_execution_failure where run_id=?",Long.class,run);
    }
    public List<Failure> failures(UUID run,int offset,int size) {
        return jdbc.query("""
                select f.execution_attempt,f.error_code,f.disposition,f.created_at,d.status publish_status,d.published_at
                from analysis_execution_failure f left join analysis_recovery_dispatch d
                  on d.analysis_run_id=f.run_id and d.dedup_key='failure:'||f.claim_token::text
                where f.run_id=? order by f.execution_attempt desc,f.created_at desc limit ? offset ?
                """,(r,n)->new Failure(r.getInt("execution_attempt"),r.getString("error_code"),r.getString("disposition"),
                instant(r,"created_at"),r.getString("publish_status"),instant(r,"published_at")),run,size,offset);
    }
    public boolean exists(UUID run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from analysis_run where id=?)",Boolean.class,run));
    }
    public long caseVersion(UUID id) { return jdbc.queryForObject("select version from invoice_case where id=?",Long.class,id); }
    public int latestInputVersion(UUID id) {
        return jdbc.queryForObject("select coalesce(max(version_number),0) from evidence_bundle where invoice_case_id=?",Integer.class,id);
    }
    public void reserveRetry(UUID run,int attempt) {
        int changed=jdbc.update("update analysis_run set status='QUEUED',attempt_limit=execution_attempt+3,updated_at=clock_timestamp()"
                + " where id=? and status='DEAD_LETTERED' and execution_attempt=?",run,attempt);
        if(changed!=1) throw new IllegalStateException("Locked retry state changed");
    }
}
