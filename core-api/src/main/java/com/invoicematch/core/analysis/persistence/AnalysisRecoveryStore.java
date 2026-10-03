package com.invoicematch.core.analysis.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** SQL and durable checkpoint writes; application owns recovery decisions. */
@Repository
public class AnalysisRecoveryStore {
    private final JdbcTemplate jdbc;
    public AnalysisRecoveryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> failure(UUID run, UUID token) {
        return jdbc.query("select disposition from analysis_execution_failure where run_id=? and claim_token=?",
                (r,n) -> r.getString(1), run, token).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> pendingDeadline(UUID run) {
        return jdbc.query("select o.next_attempt_at from analysis_recovery_dispatch o"
                + " join analysis_run r on r.id=o.analysis_run_id"
                + " join analysis_execution_failure f on f.run_id=r.id and f.execution_attempt=r.execution_attempt"
                + " where r.id=? and o.dedup_key='failure:'||f.claim_token::text and o.destination='REQUEST'",
                (r,n) -> r.getTimestamp(1) == null ? null : r.getTimestamp(1).toInstant(), run)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retryDue(UUID run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select coalesce(min(o.next_attempt_at)<=clock_timestamp(),false)"
                + " from analysis_recovery_dispatch o join analysis_run r on r.id=o.analysis_run_id"
                + " join analysis_execution_failure f on f.run_id=r.id and f.execution_attempt=r.execution_attempt"
                + " where r.id=? and o.dedup_key='failure:'||f.claim_token::text and o.destination='REQUEST'", Boolean.class, run));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkpoint(AnalysisRunSnapshot run, String code, String disposition, String destination, Duration delay) {
        jdbc.update("insert into analysis_execution_failure(run_id,claim_token,execution_attempt,error_code,disposition)"
                + " values (?,?,?,?,?)", run.runId(), run.executionToken(), run.executionAttempt(), code, disposition);
        int changed = jdbc.update("update analysis_run set status=?,execution_token=null,lease_until=null,updated_at=clock_timestamp()"
                + " where id=? and status='RUNNING' and execution_token=?", disposition, run.runId(), run.executionToken());
        if (changed != 1) throw new IllegalStateException("Recovery claim lost");
        dispatch(run.runId(), "failure:" + run.executionToken(), destination, delay, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void dispatch(UUID run, String key, String destination, Duration delay, boolean waitForLease) {
        int changed = jdbc.update("insert into analysis_recovery_dispatch(id,analysis_run_id,event_id,dedup_key,destination,"
                + "schema_version,payload,next_attempt_at) select ?,r.id,o.id,?,?,o.schema_version,o.payload,"
                + "greatest(clock_timestamp() + cast(? as double precision)*interval '1 second',"
                + "case when ? then coalesce(r.lease_until,clock_timestamp()) else clock_timestamp() end)"
                + " from analysis_run r join analysis_request_outbox o on o.analysis_run_id=r.id where r.id=?"
                + " on conflict(analysis_run_id,dedup_key) do nothing",
                UUID.randomUUID(), key, destination, delay.toMillis()/1000.0, waitForLease, run);
        if (changed == 0 && jdbc.queryForObject("select count(*) from analysis_recovery_dispatch where analysis_run_id=? and dedup_key=?",
                Integer.class, run, key) != 1) throw new IllegalStateException("Missing recovery request");
    }

    public record Dispatch(UUID id, UUID eventId, UUID token, String destination, String payload) {}

    @Transactional
    public Optional<Dispatch> claim(Duration lease) {
        return jdbc.query("with candidate as (select o.id from analysis_recovery_dispatch o join analysis_run r on r.id=o.analysis_run_id"
                + " where o.status='READY' and o.next_attempt_at<=clock_timestamp() and r.status<>'STALE'"
                + " order by o.next_attempt_at,o.id for update of o skip locked limit 1)"
                + " update analysis_recovery_dispatch o set status='CLAIMED',claim_token=?,"
                + "lease_until=clock_timestamp()+cast(? as double precision)*interval '1 second',attempt_count=attempt_count+1"
                + " from candidate c where o.id=c.id returning o.id,o.event_id,o.claim_token,o.destination,o.payload::text",
                (r,n) -> new Dispatch(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getString(5)),
                UUID.randomUUID(), lease.toMillis()/1000.0).stream().findFirst();
    }

    @Transactional
    public void recover() {
        jdbc.update("update analysis_recovery_dispatch o set status='CANCELLED',claim_token=null,lease_until=null"
                + " from analysis_run r where r.id=o.analysis_run_id and r.status='STALE' and o.status in ('READY','CLAIMED')");
        jdbc.update("update analysis_recovery_dispatch set status='READY',claim_token=null,lease_until=null,"
                + "next_attempt_at=clock_timestamp() where status='CLAIMED' and lease_until<=clock_timestamp()");
    }

    @Transactional
    public boolean settle(Dispatch d, boolean published, String error, Duration delay) {
        return jdbc.update("update analysis_recovery_dispatch set status=?,claim_token=null,lease_until=null,"
                + "published_at=case when ? then clock_timestamp() else null end,last_error_code=?,"
                + "next_attempt_at=case when ? then next_attempt_at else clock_timestamp()+cast(? as double precision)*interval '1 second' end"
                + " where id=? and status='CLAIMED' and claim_token=? and lease_until>clock_timestamp()",
                published ? "PUBLISHED" : "READY", published, error, published, delay.toMillis()/1000.0, d.id(), d.token()) == 1;
    }
}
