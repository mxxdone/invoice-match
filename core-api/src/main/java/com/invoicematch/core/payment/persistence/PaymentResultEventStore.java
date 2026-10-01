package com.invoicematch.core.payment.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for the verified payment-result webhook: event scoping/dedup,
 * canonical outbox context locking and typed reads, the conditional
 * acknowledged/failed state transitions, and the result-event insert.
 *
 * <p>It owns only SQL and typed rows. The replay/conflict/state policy and the
 * single {@code @Transactional} boundary stay in
 * {@code PaymentResultWebhookApplicationService}; every method here joins that
 * caller transaction as {@link Propagation#MANDATORY}, so the store can never be
 * invoked as an independently committing unit and the acknowledged write order
 * {@code invoice_case -> payment_request -> outbox_event -> event insert} (or
 * payment/outbox then insert for a failure) is all-or-nothing.
 */
@Repository
public class PaymentResultEventStore {

    private final JdbcTemplate jdbc;

    public PaymentResultEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes requests for the same {@code (provider, externalEventId)}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockEventScope(String provider, String externalEventId) {
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, rowNum) -> Boolean.TRUE,
                provider + ":" + externalEventId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecordedPaymentResultEvent> findRecordedEvent(String provider, String externalEventId) {
        List<RecordedPaymentResultEvent> rows = jdbc.query(
                "select payload_hash from payment_result_event where provider = ? and external_event_id = ?",
                (rs, rowNum) -> new RecordedPaymentResultEvent(rs.getString("payload_hash")),
                provider,
                externalEventId);
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PaymentOutboxContext> findOutboxContext(String externalPaymentKey) {
        List<PaymentOutboxContext> rows = jdbc.query(
                "select id, payment_request_id, invoice_case_id from outbox_event where idempotency_key = ?",
                (rs, rowNum) -> new PaymentOutboxContext(
                        rs.getObject("id", UUID.class),
                        rs.getObject("payment_request_id", UUID.class),
                        rs.getObject("invoice_case_id", UUID.class)),
                externalPaymentKey);
        return rows.stream().findFirst();
    }

    /**
     * Takes the canonical {@code invoice_case -> payment_request -> outbox_event}
     * row locks. No HTTP or external call runs while these locks are held.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInCanonicalOrder(PaymentOutboxContext context) {
        jdbc.queryForObject(
                "select id from invoice_case where id = ? for update", UUID.class, context.invoiceCaseId());
        jdbc.queryForList(
                "select status from payment_request where id = ? for update",
                String.class,
                context.paymentRequestId());
        jdbc.queryForList(
                "select status from outbox_event where id = ? for update", String.class, context.outboxEventId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentExportStatuses readStatuses(PaymentOutboxContext context) {
        String paymentStatus = jdbc.queryForObject(
                "select status from payment_request where id = ?", String.class, context.paymentRequestId());
        String outboxStatus = jdbc.queryForObject(
                "select status from outbox_event where id = ?", String.class, context.outboxEventId());
        return new PaymentExportStatuses(paymentStatus, outboxStatus);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int exportCase(UUID invoiceCaseId, Instant now) {
        return jdbc.update(
                "update invoice_case set status = 'EXPORTED', version = version + 1, updated_at = ?"
                        + " where id = ? and status = 'EXPORT_PENDING'",
                Timestamp.from(now),
                invoiceCaseId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int acknowledgePayment(UUID paymentRequestId) {
        return jdbc.update(
                "update payment_request set status = 'ACKNOWLEDGED'"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                paymentRequestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int acknowledgeOutbox(UUID outboxEventId) {
        return jdbc.update(
                "update outbox_event set status = 'DELIVERED', delivered_at = clock_timestamp(),"
                        + " last_error_code = null, worker_id = null, claim_token = null,"
                        + " lease_expires_at = null, updated_at = clock_timestamp()"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                outboxEventId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int failPayment(UUID paymentRequestId) {
        return jdbc.update(
                "update payment_request set status = 'FAILED'"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                paymentRequestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int failOutbox(UUID outboxEventId) {
        return jdbc.update(
                "update outbox_event set status = 'FAILED', last_error_code = 'ERP_RESULT_FAILED',"
                        + " worker_id = null, claim_token = null, lease_expires_at = null,"
                        + " updated_at = clock_timestamp()"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                outboxEventId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertResult(PaymentResultEventInsert event, Instant receivedAt) {
        jdbc.update(
                "insert into payment_result_event (id, provider, external_event_id, external_payment_key,"
                        + " payment_request_id, outbox_event_id, outcome, payload_hash, external_reference,"
                        + " received_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                event.provider(),
                event.externalEventId(),
                event.externalPaymentKey(),
                event.paymentRequestId(),
                event.outboxEventId(),
                event.outcome(),
                event.payloadHash(),
                event.externalReference(),
                Timestamp.from(receivedAt));
    }

    /** Canonical outbox identity of an export referenced by a payment result. */
    public record PaymentOutboxContext(UUID outboxEventId, UUID paymentRequestId, UUID invoiceCaseId) {
    }

    /** Recorded dedup key payload of one already-applied result event. */
    public record RecordedPaymentResultEvent(String payloadHash) {
    }

    /** Current payment and outbox status read under the canonical locks. */
    public record PaymentExportStatuses(String paymentStatus, String outboxStatus) {
    }

    /** Everything persisted for one first-applied result event. */
    public record PaymentResultEventInsert(
            String provider,
            String externalEventId,
            String externalPaymentKey,
            UUID paymentRequestId,
            UUID outboxEventId,
            String outcome,
            String payloadHash,
            String externalReference) {
    }
}
