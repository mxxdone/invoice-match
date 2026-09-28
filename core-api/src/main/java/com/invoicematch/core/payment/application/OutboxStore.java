package com.invoicematch.core.payment.application;

import com.invoicematch.core.payment.domain.DeliveryAttemptOutcome;
import com.invoicematch.core.payment.domain.PaymentExportPayload;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim/lease/finalization persistence. Every method is a short transaction and
 * every transition is a claim-token compare-and-set, so a stale worker can never
 * overwrite the current state. No method holds a transaction across HTTP.
 *
 * <p>All lease deadlines and expiry comparisons use PostgreSQL
 * {@code clock_timestamp()}, so multiple application nodes cannot disagree about
 * time. Evidence is inserted before the outbox status flips (while the event is
 * still CLAIMED/SENDING) so the attempt INSERT guard can prove it belongs to the
 * live claim; a deferred constraint trigger then binds the outcome to the
 * committed state.
 *
 * <p>Lock order mirrors P1-07: {@code invoice_case -> payment_request ->
 * outbox_event}. Recovery and failure finalization never touch the case, so they
 * use the {@code payment_request -> outbox_event} prefix. Claiming only locks
 * outbox rows (with SKIP LOCKED).
 */
@Service
public class OutboxStore {

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims up to {@code batch} ready events with {@code FOR UPDATE SKIP
     * LOCKED}. Each row gets an opaque token, the worker id and a DB-time lease
     * deadline in the same short transaction. HTTP never runs under this
     * transaction.
     */
    @Transactional
    public List<ClaimedEvent> claimBatch(int batch, String workerId, Duration lease) {
        List<UUID> ids = jdbc.queryForList(
                "select id from outbox_event"
                        + " where status = 'READY'"
                        + " and (next_attempt_at is null or next_attempt_at <= clock_timestamp())"
                        + " order by created_at, id"
                        + " for update skip locked"
                        + " limit ?",
                UUID.class,
                batch);
        List<ClaimedEvent> claimed = new java.util.ArrayList<>(ids.size());
        for (UUID id : ids) {
            UUID token = UUID.randomUUID();
            int updated = jdbc.update(
                    "update outbox_event"
                            + " set status = 'CLAIMED', worker_id = ?, claim_token = ?,"
                            + " lease_expires_at = clock_timestamp() + (cast(? as double precision) * interval '1 second'),"
                            + " updated_at = clock_timestamp()"
                            + " where id = ? and status = 'READY'",
                    workerId,
                    token,
                    seconds(lease),
                    id);
            if (updated == 1) {
                claimed.add(new ClaimedEvent(id, token));
            }
        }
        return claimed;
    }

    /**
     * Commits CLAIMED -> SENDING (and PaymentRequest -> SENDING) immediately
     * before HTTP. The SENDING attempt is written while the event is still
     * CLAIMED, then the state flips. Returns empty when the event is no longer
     * claimable; throws (rolling back) when a concurrent path changed the lease.
     */
    @Transactional
    public Optional<SendingEvent> beginSending(UUID eventId, UUID claimToken, String workerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select o.status, o.claim_token, o.worker_id, o.attempt_count, o.payment_request_id,"
                        + " o.invoice_case_id, o.idempotency_key, o.export_version,"
                        + " p.purchase_order_id, p.review_snapshot_id, p.review_payload_hash,"
                        + " p.external_request_key, p.amount, p.currency"
                        + " from outbox_event o join payment_request p on p.id = o.payment_request_id"
                        + " where o.id = ?",
                eventId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        if (!"CLAIMED".equals(row.get("status"))
                || !claimToken.equals(row.get("claim_token"))
                || !workerId.equals(row.get("worker_id"))) {
            return Optional.empty();
        }
        UUID paymentRequestId = (UUID) row.get("payment_request_id");
        int nextAttempt = ((Number) row.get("attempt_count")).intValue() + 1;

        // payment_request -> outbox_event lock order.
        List<String> paymentStatus = jdbc.queryForList(
                "select status from payment_request where id = ? for update", String.class, paymentRequestId);
        if (paymentStatus.isEmpty()
                || !(paymentStatus.get(0).equals("NOT_SENT") || paymentStatus.get(0).equals("RETRY_SCHEDULED"))) {
            throw new StaleClaimException(eventId, "payment request is not sendable");
        }
        // Lock the event and re-verify the claim under the payment lock.
        Map<String, Object> locked = jdbc.queryForMap(
                "select status, claim_token, worker_id, attempt_count from outbox_event where id = ? for update",
                eventId);
        if (!"CLAIMED".equals(locked.get("status"))
                || !claimToken.equals(locked.get("claim_token"))
                || !workerId.equals(locked.get("worker_id"))) {
            throw new StaleClaimException(eventId, "claim changed before HTTP");
        }

        String payload = PaymentExportPayload.forPaymentRequestFields(
                        paymentRequestId,
                        (UUID) row.get("invoice_case_id"),
                        (String) row.get("purchase_order_id"),
                        (UUID) row.get("review_snapshot_id"),
                        (String) row.get("review_payload_hash"),
                        (String) row.get("external_request_key"),
                        ((Number) row.get("amount")).longValue(),
                        (String) row.get("currency"),
                        ((Number) row.get("export_version")).longValue())
                .canonicalJson();

        recordAttempt(eventId, paymentRequestId, claimToken, workerId, nextAttempt,
                DeliveryAttemptOutcome.SENDING, null, null, null);
        int box = jdbc.update(
                "update outbox_event set status = 'SENDING', attempt_count = attempt_count + 1,"
                        + " updated_at = clock_timestamp()"
                        + " where id = ? and status = 'CLAIMED' and claim_token = ?"
                        + " and lease_expires_at > clock_timestamp()",
                eventId,
                claimToken);
        if (box != 1) {
            throw new StaleClaimException(eventId, "claim expired before HTTP");
        }
        int payment = jdbc.update(
                "update payment_request set status = 'SENDING'"
                        + " where id = ? and status in ('NOT_SENT', 'RETRY_SCHEDULED')",
                paymentRequestId);
        if (payment != 1) {
            throw new StaleClaimException(eventId, "payment request is not sendable");
        }
        return Optional.of(new SendingEvent(
                eventId,
                claimToken,
                paymentRequestId,
                (UUID) row.get("invoice_case_id"),
                (String) row.get("idempotency_key"),
                payload,
                nextAttempt));
    }

    /**
     * Definite 2xx: outbox DELIVERED, payment ACKNOWLEDGED, case EXPORTED. The
     * case timestamp uses the application clock to stay consistent with the rest
     * of the business writes; lease/attempt times use DB time.
     */
    @Transactional
    public void finalizeSuccess(SendingEvent event, int httpStatus, Instant now, String workerId) {
        assertCurrentClaim(event);
        int caseUpdated = jdbc.update(
                "update invoice_case set status = 'EXPORTED', version = version + 1, updated_at = ?"
                        + " where id = ? and status = 'EXPORT_PENDING'",
                Timestamp.from(now),
                event.invoiceCaseId());
        if (caseUpdated != 1) {
            throw new StaleClaimException(event.eventId(), "case is not EXPORT_PENDING");
        }
        int payment = jdbc.update(
                "update payment_request set status = 'ACKNOWLEDGED'"
                        + " where id = ? and status = 'SENDING'",
                event.paymentRequestId());
        if (payment != 1) {
            throw new StaleClaimException(event.eventId(), "payment request is not SENDING");
        }
        recordAttempt(event.eventId(), event.paymentRequestId(), event.claimToken(), workerId,
                event.attemptNumber(), DeliveryAttemptOutcome.ACKNOWLEDGED, httpStatus, null, null);
        int box = jdbc.update(
                "update outbox_event set status = 'DELIVERED', delivered_at = clock_timestamp(),"
                        + " last_error_code = null, worker_id = null, claim_token = null,"
                        + " lease_expires_at = null, updated_at = clock_timestamp()"
                        + " where id = ? and status = 'SENDING' and claim_token = ?",
                event.eventId(),
                event.claimToken());
        if (box != 1) {
            throw new StaleClaimException(event.eventId(), "outbox is not SENDING for this token");
        }
    }

    /** Definite non-retryable outcome: outbox/payment FAILED, case unchanged. */
    @Transactional
    public void finalizeFailure(SendingEvent event, Integer httpStatus, String errorCode, String detail,
            String workerId) {
        assertCurrentClaim(event);
        int payment = jdbc.update(
                "update payment_request set status = 'FAILED' where id = ? and status = 'SENDING'",
                event.paymentRequestId());
        if (payment != 1) {
            throw new StaleClaimException(event.eventId(), "payment request is not SENDING");
        }
        recordAttempt(event.eventId(), event.paymentRequestId(), event.claimToken(), workerId,
                event.attemptNumber(), DeliveryAttemptOutcome.FAILED, httpStatus, errorCode, detail);
        int box = jdbc.update(
                "update outbox_event set status = 'FAILED', last_error_code = ?,"
                        + " worker_id = null, claim_token = null, lease_expires_at = null,"
                        + " updated_at = clock_timestamp()"
                        + " where id = ? and status = 'SENDING' and claim_token = ?",
                errorCode,
                event.eventId(),
                event.claimToken());
        if (box != 1) {
            throw new StaleClaimException(event.eventId(), "outbox is not SENDING for this token");
        }
    }

    /** Explicit 429: outbox back to READY after a bounded DB-time backoff. */
    @Transactional
    public void scheduleRetry(SendingEvent event, int httpStatus, Duration backoff, String detail,
            String workerId) {
        assertCurrentClaim(event);
        int payment = jdbc.update(
                "update payment_request set status = 'RETRY_SCHEDULED' where id = ? and status = 'SENDING'",
                event.paymentRequestId());
        if (payment != 1) {
            throw new StaleClaimException(event.eventId(), "payment request is not SENDING");
        }
        recordAttempt(event.eventId(), event.paymentRequestId(), event.claimToken(), workerId,
                event.attemptNumber(), DeliveryAttemptOutcome.RETRY_SCHEDULED, httpStatus, "RATE_LIMITED", detail);
        int box = jdbc.update(
                "update outbox_event set status = 'READY',"
                        + " next_attempt_at = clock_timestamp() + (cast(? as double precision) * interval '1 second'),"
                        + " last_error_code = 'RATE_LIMITED', worker_id = null, claim_token = null,"
                        + " lease_expires_at = null, updated_at = clock_timestamp()"
                        + " where id = ? and status = 'SENDING' and claim_token = ?",
                seconds(backoff),
                event.eventId(),
                event.claimToken());
        if (box != 1) {
            throw new StaleClaimException(event.eventId(), "outbox is not SENDING for this token");
        }
    }

    /** Ambiguous outcome: outbox/payment RESULT_UNKNOWN, never auto-resent. */
    @Transactional
    public void markResultUnknown(SendingEvent event, Integer httpStatus, String errorCode, String detail,
            String workerId) {
        assertCurrentClaim(event);
        int payment = jdbc.update(
                "update payment_request set status = 'RESULT_UNKNOWN' where id = ? and status = 'SENDING'",
                event.paymentRequestId());
        if (payment != 1) {
            throw new StaleClaimException(event.eventId(), "payment request is not SENDING");
        }
        recordAttempt(event.eventId(), event.paymentRequestId(), event.claimToken(), workerId,
                event.attemptNumber(), DeliveryAttemptOutcome.RESULT_UNKNOWN, httpStatus, errorCode, detail);
        int box = jdbc.update(
                "update outbox_event set status = 'RESULT_UNKNOWN', last_error_code = ?,"
                        + " worker_id = null, claim_token = null, lease_expires_at = null,"
                        + " updated_at = clock_timestamp()"
                        + " where id = ? and status = 'SENDING' and claim_token = ?",
                errorCode,
                event.eventId(),
                event.claimToken());
        if (box != 1) {
            throw new StaleClaimException(event.eventId(), "outbox is not SENDING for this token");
        }
    }

    /**
     * Lease recovery using DB time. Expired CLAIMED events safely return to
     * READY (HTTP never started); expired SENDING events become RESULT_UNKNOWN
     * with LEASE_EXPIRED evidence and are never automatically resent. Returns the
     * number of recovered events.
     */
    @Transactional
    public int recoverExpired() {
        int returned = jdbc.update(
                "update outbox_event set status = 'READY', next_attempt_at = clock_timestamp(),"
                        + " worker_id = null, claim_token = null, lease_expires_at = null,"
                        + " updated_at = clock_timestamp()"
                        + " where status = 'CLAIMED' and lease_expires_at <= clock_timestamp()");

        // Identify expired SENDING candidates WITHOUT locking them, then take
        // locks in the canonical order payment_request -> outbox_event.
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "select id, payment_request_id, claim_token, worker_id, attempt_count"
                        + " from outbox_event where status = 'SENDING' and lease_expires_at <= clock_timestamp()"
                        + " order by id");
        int recovered = returned;
        for (Map<String, Object> row : candidates) {
            UUID eventId = (UUID) row.get("id");
            UUID paymentRequestId = (UUID) row.get("payment_request_id");
            UUID claimToken = (UUID) row.get("claim_token");
            String workerId = (String) row.get("worker_id");
            int attemptNumber = ((Number) row.get("attempt_count")).intValue();

            List<String> paymentStatus = jdbc.queryForList(
                    "select status from payment_request where id = ? for update",
                    String.class,
                    paymentRequestId);
            if (paymentStatus.isEmpty() || !"SENDING".equals(paymentStatus.get(0))) {
                continue;  // another worker already finalized; never clobber
            }
            Map<String, Object> locked = jdbc.queryForMap(
                    "select status, claim_token, lease_expires_at <= clock_timestamp() as expired"
                            + " from outbox_event where id = ? for update",
                    eventId);
            if (!"SENDING".equals(locked.get("status"))
                    || !claimToken.equals(locked.get("claim_token"))
                    || !Boolean.TRUE.equals(locked.get("expired"))) {
                throw new StaleClaimException(eventId, "outbox changed during lease recovery");
            }
            recordAttempt(eventId, paymentRequestId, claimToken, workerId, Math.max(attemptNumber, 1),
                    DeliveryAttemptOutcome.LEASE_EXPIRED, null, "LEASE_EXPIRED", null);
            int payment = jdbc.update(
                    "update payment_request set status = 'RESULT_UNKNOWN'"
                            + " where id = ? and status = 'SENDING'",
                    paymentRequestId);
            if (payment != 1) {
                throw new StaleClaimException(eventId, "payment changed during lease recovery");
            }
            int box = jdbc.update(
                    "update outbox_event set status = 'RESULT_UNKNOWN', last_error_code = 'LEASE_EXPIRED',"
                            + " worker_id = null, claim_token = null, lease_expires_at = null,"
                            + " updated_at = clock_timestamp()"
                            + " where id = ? and status = 'SENDING' and claim_token = ?",
                    eventId,
                    claimToken);
            if (box != 1) {
                throw new StaleClaimException(eventId, "outbox changed during lease recovery");
            }
            recovered++;
        }
        return recovered;
    }

    private void assertCurrentClaim(SendingEvent event) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status, claim_token from outbox_event where id = ?", event.eventId());
        if (rows.isEmpty()
                || !"SENDING".equals(rows.get(0).get("status"))
                || !event.claimToken().equals(rows.get(0).get("claim_token"))) {
            throw new StaleClaimException(event.eventId(), "no live SENDING claim for this token");
        }
    }

    private void recordAttempt(
            UUID eventId,
            UUID paymentRequestId,
            UUID claimToken,
            String workerId,
            int attemptNumber,
            DeliveryAttemptOutcome outcome,
            Integer httpStatus,
            String errorCode,
            String detail) {
        jdbc.update(
                "insert into outbox_delivery_attempt (id, outbox_event_id, payment_request_id, claim_token,"
                        + " worker_id, attempt_number, outcome, http_status, error_code, detail, occurred_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())",
                UUID.randomUUID(),
                eventId,
                paymentRequestId,
                claimToken,
                workerId == null || workerId.isBlank() ? "unknown" : workerId,
                Math.max(attemptNumber, 1),
                outcome.name(),
                httpStatus,
                errorCode,
                detail);
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
