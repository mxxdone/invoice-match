package com.invoicematch.core.analysis.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-only claim/lease/finalize store for the analysis-request Outbox.
 * Every method is a short transaction and every mutation is a conditional
 * {@code UPDATE}; nothing here locks or updates the {@code analysis_run} or the
 * {@code invoice_case}. Lease deadlines and expiry comparisons use PostgreSQL
 * {@code clock_timestamp()}, so separate application nodes cannot disagree about
 * time, and no method holds a transaction across a broker call.
 *
 * <p>The relay only ever claims a due READY row whose run is still QUEUED
 * ({@code FOR UPDATE OF outbox SKIP LOCKED}), one row per call. A stale token
 * can never finalize, release or cancel a newer claim or a terminal row.
 */
@Service
public class AnalysisOutboxStore {

    private final JdbcTemplate jdbc;

    public AnalysisOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the single oldest due READY request whose run is still QUEUED,
     * assigning a fresh token, a DB-time lease and one attempt. Returns empty
     * when nothing is due or every due row is locked by another worker.
     */
    @Transactional
    public Optional<ClaimedAnalysisRequest> claimOne(String workerId, Duration lease) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select o.id, o.analysis_run_id, o.payload::text as payload"
                        + " from analysis_request_outbox o"
                        + " join analysis_run r on r.id = o.analysis_run_id"
                        + " where o.status = 'READY'"
                        + " and o.next_attempt_at <= clock_timestamp()"
                        + " and r.status = 'QUEUED'"
                        + " order by o.created_at, o.id"
                        + " for update of o skip locked"
                        + " limit 1");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        UUID eventId = (UUID) row.get("id");
        UUID runId = (UUID) row.get("analysis_run_id");
        UUID token = UUID.randomUUID();
        int updated = jdbc.update(
                "update analysis_request_outbox"
                        + " set status = 'CLAIMED', claim_token = ?,"
                        + " lease_until = clock_timestamp() + (cast(? as double precision) * interval '1 second'),"
                        + " attempt_count = attempt_count + 1"
                        + " where id = ? and status = 'READY'",
                token,
                seconds(lease),
                eventId);
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(new ClaimedAnalysisRequest(eventId, runId, token, (String) row.get("payload")));
    }

    /**
     * Recovers expired claims and cleans up reservations the business has
     * superseded. First every READY/CLAIMED request of a STALE run is CANCELLED
     * (a publish may already be in flight — the consumer re-validates the run).
     * Then a CLAIMED row whose lease expired is returned to READY and immediately
     * due, because HTTP never started under a CLAIMED claim. Returns the number
     * of rows returned to READY.
     */
    @Transactional
    public int recoverExpired() {
        jdbc.update(
                "update analysis_request_outbox o"
                        + " set status = 'CANCELLED', claim_token = null, lease_until = null"
                        + " from analysis_run r"
                        + " where r.id = o.analysis_run_id"
                        + " and r.status = 'STALE'"
                        + " and o.status in ('READY', 'CLAIMED')");
        return jdbc.update(
                "update analysis_request_outbox"
                        + " set status = 'READY', next_attempt_at = clock_timestamp(),"
                        + " claim_token = null, lease_until = null"
                        + " where status = 'CLAIMED' and lease_until <= clock_timestamp()");
    }

    /**
     * Conditional {@code CLAIMED -> PUBLISHED}. The row must still carry this
     * token under an unexpired lease, so a stale worker whose lease lapsed can
     * never publish-mark a row it no longer owns.
     */
    @Transactional
    public boolean finalizePublished(UUID eventId, UUID claimToken) {
        int updated = jdbc.update(
                "update analysis_request_outbox"
                        + " set status = 'PUBLISHED', published_at = clock_timestamp(),"
                        + " claim_token = null, lease_until = null, last_error_code = null"
                        + " where id = ? and status = 'CLAIMED' and claim_token = ?"
                        + " and lease_until > clock_timestamp()",
                eventId,
                claimToken);
        return updated == 1;
    }

    /**
     * Conditional release after a failed attempt: back to READY, due after a
     * bounded DB-time backoff, with only the fixed classification code stored.
     */
    @Transactional
    public boolean releaseForRetry(UUID eventId, UUID claimToken, String errorCode, Duration retryDelay) {
        int updated = jdbc.update(
                "update analysis_request_outbox"
                        + " set status = 'READY',"
                        + " next_attempt_at = clock_timestamp() + (cast(? as double precision) * interval '1 second'),"
                        + " claim_token = null, lease_until = null, last_error_code = ?"
                        + " where id = ? and status = 'CLAIMED' and claim_token = ?"
                        + " and lease_until > clock_timestamp()",
                seconds(retryDelay),
                normalizeErrorCode(errorCode),
                eventId,
                claimToken);
        return updated == 1;
    }

    /**
     * Conditional cancel of a superseded reservation: only READY/CLAIMED rows
     * are cancelled and their lease removed. A PUBLISHED row is terminal and is
     * preserved. Joins the caller's submission transaction.
     */
    @Transactional
    public int cancelSuperseded(UUID analysisRunId) {
        return jdbc.update(
                "update analysis_request_outbox"
                        + " set status = 'CANCELLED', claim_token = null, lease_until = null"
                        + " where analysis_run_id = ? and status in ('READY', 'CLAIMED')",
                analysisRunId);
    }

    private static String normalizeErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "UNKNOWN";
        }
        String code = errorCode.strip();
        return code.length() <= 64 ? code : code.substring(0, 64);
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
