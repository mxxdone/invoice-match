package com.invoicematch.core.analysis;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared fixture for the P2-06 relay tests. It turns analysis reservations on
 * (so a document submission reserves a run and a READY request) and exposes the
 * relay columns and lease helpers used by the store, relay and RabbitMQ tests.
 */
public abstract class AbstractAnalysisRelayIntegrationTest extends AbstractAnalysisIntegrationTest {

    @DynamicPropertySource
    static void analysisReservationEnabled(DynamicPropertyRegistry registry) {
        registry.add("analysis.request.enabled", () -> true);
    }

    protected UUID outboxId(UUID caseId, int inputVersion) {
        return jdbc.queryForObject(
                "select o.id from analysis_request_outbox o"
                        + " join analysis_run r on r.id = o.analysis_run_id"
                        + " where r.invoice_case_id = ? and r.input_version = ?",
                UUID.class,
                caseId,
                inputVersion);
    }

    /** The full relay view of one reservation, including run status. */
    protected Map<String, Object> outboxRow(UUID caseId, int inputVersion) {
        return jdbc.queryForMap(
                "select o.id, o.analysis_run_id, o.status, o.payload::text as payload, o.attempt_count,"
                        + " o.next_attempt_at, o.claim_token, o.lease_until, o.published_at, o.last_error_code,"
                        + " r.status as run_status"
                        + " from analysis_request_outbox o"
                        + " join analysis_run r on r.id = o.analysis_run_id"
                        + " where r.invoice_case_id = ? and r.input_version = ?",
                caseId,
                inputVersion);
    }

    /**
     * Bounded DB-time wait for a short lease to actually expire. The lease
     * deadline is immutable outside a status transition, so a test proves
     * recovery by letting a real lease lapse rather than rewriting the deadline.
     */
    protected void awaitLeaseExpiry(UUID eventId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean expired = jdbc.queryForObject(
                    "select lease_until <= clock_timestamp() from analysis_request_outbox where id = ?",
                    Boolean.class,
                    eventId);
            if (Boolean.TRUE.equals(expired)) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for lease expiry");
            }
        }
        throw new AssertionError("lease for " + eventId + " did not expire within 10s");
    }

    /** Test-only: make a READY retry backoff immediately due (nextAttemptAt only). */
    protected void releaseBackoff(UUID eventId) {
        jdbc.update("update analysis_request_outbox"
                + " set next_attempt_at = clock_timestamp() - interval '1 minute' where id = ?", eventId);
    }
}
