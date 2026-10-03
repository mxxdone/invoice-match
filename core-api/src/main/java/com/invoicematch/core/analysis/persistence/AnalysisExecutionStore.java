package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-only store for the analysis execution lifecycle. It owns the SQL,
 * the {@code invoice_case -> analysis_run} lock order and the conditional
 * updates; it never decides a disposition, validates a document or builds an
 * HTTP shape.
 *
 * <p>Every method joins the caller's short transaction ({@code MANDATORY}) and
 * every mutation is a conditional {@code UPDATE} guarded by the current status
 * and execution token, so a stale token can never extend, steal or terminalize a
 * claim it no longer owns. Lease deadlines and expiry comparisons use PostgreSQL
 * {@code clock_timestamp()}, not the JVM clock.
 */
@Service
public class AnalysisExecutionStore {

    private final JdbcTemplate jdbc;

    public AnalysisExecutionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the case and then the run, in the same order a submission takes them,
     * and returns the authoritative row. The caller must already be in a
     * transaction. Empty when no run has that id.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AnalysisRunSnapshot> lockByRunId(UUID runId) {
        List<UUID> caseIds = jdbc.query(
                "select invoice_case_id from analysis_run where id = ?",
                (rs, n) -> rs.getObject("invoice_case_id", UUID.class),
                runId);
        if (caseIds.isEmpty()) {
            return Optional.empty();
        }
        // Same lock order as submit: the case, then the run.
        jdbc.query("select id from invoice_case where id = ? for update",
                (rs, n) -> rs.getObject("id", UUID.class), caseIds.get(0));
        return jdbc.query(
                "select id, invoice_case_id, evidence_bundle_id, input_version, evidence_payload_hash,"
                        + " workflow_version, status, execution_token, lease_until, execution_attempt, attempt_limit,"
                        + " (lease_until is not null and lease_until > clock_timestamp()) as lease_active"
                        + " from analysis_run where id = ? for update",
                (rs, n) -> new AnalysisRunSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getObject("invoice_case_id", UUID.class),
                        rs.getObject("evidence_bundle_id", UUID.class),
                        rs.getInt("input_version"),
                        rs.getString("evidence_payload_hash"),
                        rs.getString("workflow_version"),
                        AnalysisRunStatus.valueOf(rs.getString("status")),
                        rs.getObject("execution_token", UUID.class),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                        rs.getInt("execution_attempt"),
                        rs.getBoolean("lease_active"), rs.getInt("attempt_limit")),
                runId).stream().findFirst();
    }

    /** The immutable Outbox event id connected to the run, if any. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> findRequestEventId(UUID runId) {
        return jdbc.query("select id from analysis_request_outbox where analysis_run_id = ?",
                (rs, n) -> rs.getObject("id", UUID.class), runId).stream().findFirst();
    }

    /**
     * Claims (or reclaims) the run: {@code QUEUED} or an expired {@code RUNNING}
     * becomes a fresh {@code RUNNING} claim under a new token and lease, and the
     * attempt advances by one. Returns the DB-computed lease deadline, or empty
     * if the row is no longer claimable.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> claim(UUID runId, UUID token, Duration lease) {
        List<Instant> leases = jdbc.query(
                "update analysis_run"
                        + " set status = 'RUNNING', execution_token = ?,"
                        + " lease_until = clock_timestamp() + (cast(? as double precision) * interval '1 second'),"
                        + " execution_attempt = execution_attempt + 1, updated_at = clock_timestamp()"
                        + " where id = ? and (status in ('QUEUED', 'RETRY_SCHEDULED')"
                        + " or (status = 'RUNNING' and lease_until <= clock_timestamp()))"
                        + " returning lease_until",
                (rs, n) -> rs.getTimestamp("lease_until").toInstant(),
                token,
                seconds(lease),
                runId);
        return leases.stream().findFirst();
    }

    /**
     * Extends the lease of an active claim holding the same token. Same attempt,
     * same status. Empty if the token is stale or the lease already expired.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> heartbeat(UUID runId, UUID token, Duration lease) {
        List<Instant> leases = jdbc.query(
                "update analysis_run"
                        + " set lease_until = clock_timestamp() + (cast(? as double precision) * interval '1 second'),"
                        + " updated_at = clock_timestamp()"
                        + " where id = ? and status = 'RUNNING' and execution_token = ?"
                        + " and lease_until > clock_timestamp()"
                        + " returning lease_until",
                (rs, n) -> rs.getTimestamp("lease_until").toInstant(),
                seconds(lease),
                runId,
                token);
        return leases.stream().findFirst();
    }

    /**
     * Terminalizes an active claim ({@code RUNNING -> COMPLETED|FAILED}) and
     * drops the token/lease. Empty if the token/lease is no longer current.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finalizeTerminal(UUID runId, UUID token, AnalysisRunStatus terminal) {
        int updated = jdbc.update(
                "update analysis_run"
                        + " set status = ?, execution_token = null, lease_until = null,"
                        + " updated_at = clock_timestamp()"
                        + " where id = ? and status = 'RUNNING' and execution_token = ?"
                        + " and lease_until > clock_timestamp()",
                terminal.name(),
                runId,
                token);
        return updated == 1;
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
