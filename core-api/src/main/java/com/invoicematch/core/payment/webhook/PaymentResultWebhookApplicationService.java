package com.invoicematch.core.payment.webhook;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent application of a verified external payment result.
 *
 * <p>This is the only P1-09 writer of the payment/outbox/case tuple and it never
 * starts a send. Two writers can race for the same payment (an in-flight relay
 * finalization and an arriving webhook); both take the canonical
 * {@code invoice_case -> payment_request -> outbox_event} locks and both
 * finalizations are conditional, so exactly one wins and the loser becomes a
 * no-op. External HTTP happens before this transaction, never inside it.
 *
 * <p>Dedup and ordering are defended twice: a transaction-scoped advisory lock
 * serializes requests for the same {@code (provider, externalEventId)} so a
 * duplicate cannot double-apply, and the business state machine plus the
 * deferred DB guards reject an out-of-order or conflicting result with no side
 * effect. Only {@code ACKNOWLEDGED} reaches the {@code EXPORTED} tuple; a
 * {@code FAILED} result leaves the case {@code EXPORT_PENDING}. A result never
 * creates a PaymentRequest or a new export key.
 */
@Service
public class PaymentResultWebhookApplicationService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PaymentResultWebhookApplicationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public PaymentResultIngestResult ingest(PaymentResultCommand command) {
        lockEventScope(command.provider(), command.externalEventId());

        Optional<RecordedEvent> recorded = findRecordedEvent(command.provider(), command.externalEventId());
        if (recorded.isPresent()) {
            return replayOrConflict(recorded.get(), command);
        }

        OutboxContext context = loadOutbox(command.externalPaymentKey());
        lockInCanonicalOrder(context);

        // Re-check under the payment lock: a same-event request cannot pass the
        // advisory lock, but a re-read keeps the decision explicit and cheap.
        recorded = findRecordedEvent(command.provider(), command.externalEventId());
        if (recorded.isPresent()) {
            return replayOrConflict(recorded.get(), command);
        }
        if (command.paymentRequestId() != null && !command.paymentRequestId().equals(context.paymentRequestId())) {
            throw new PaymentResultConflictException(
                    "external payment key and payment request id do not match the recorded export");
        }

        String paymentStatus = status("payment_request", context.paymentRequestId());
        String outboxStatus = status("outbox_event", context.outboxEventId());
        return command.outcome() == PaymentResultOutcome.ACKNOWLEDGED
                ? applyAcknowledged(command, context, paymentStatus, outboxStatus)
                : applyFailed(command, context, paymentStatus, outboxStatus);
    }

    private PaymentResultIngestResult applyAcknowledged(
            PaymentResultCommand command, OutboxContext context, String paymentStatus, String outboxStatus) {
        if (paymentStatus.equals("ACKNOWLEDGED") && outboxStatus.equals("DELIVERED")) {
            insertResult(command, context);
            return PaymentResultIngestResult.REPLAY;
        }
        if (!isResolvable(paymentStatus) || !isResolvable(outboxStatus)) {
            throw new PaymentResultConflictException(
                    "acknowledged result cannot be applied from payment=" + paymentStatus
                            + " outbox=" + outboxStatus);
        }
        Instant now = clock.instant();
        int caseUpdated = jdbc.update(
                "update invoice_case set status = 'EXPORTED', version = version + 1, updated_at = ?"
                        + " where id = ? and status = 'EXPORT_PENDING'",
                Timestamp.from(now),
                context.invoiceCaseId());
        if (caseUpdated != 1) {
            throw new PaymentResultConflictException("case is not awaiting export");
        }
        int paymentUpdated = jdbc.update(
                "update payment_request set status = 'ACKNOWLEDGED'"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                context.paymentRequestId());
        if (paymentUpdated != 1) {
            throw new PaymentResultConflictException("payment is not in a resolvable state");
        }
        int outboxUpdated = jdbc.update(
                "update outbox_event set status = 'DELIVERED', delivered_at = clock_timestamp(),"
                        + " last_error_code = null, worker_id = null, claim_token = null,"
                        + " lease_expires_at = null, updated_at = clock_timestamp()"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                context.outboxEventId());
        if (outboxUpdated != 1) {
            throw new PaymentResultConflictException("outbox is not in a resolvable state");
        }
        insertResult(command, context);
        return PaymentResultIngestResult.APPLIED;
    }

    private PaymentResultIngestResult applyFailed(
            PaymentResultCommand command, OutboxContext context, String paymentStatus, String outboxStatus) {
        if (paymentStatus.equals("FAILED") && outboxStatus.equals("FAILED")) {
            insertResult(command, context);
            return PaymentResultIngestResult.REPLAY;
        }
        if (!isResolvable(paymentStatus) || !isResolvable(outboxStatus)) {
            throw new PaymentResultConflictException(
                    "failed result cannot be applied from payment=" + paymentStatus + " outbox=" + outboxStatus);
        }
        int paymentUpdated = jdbc.update(
                "update payment_request set status = 'FAILED'"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                context.paymentRequestId());
        if (paymentUpdated != 1) {
            throw new PaymentResultConflictException("payment is not in a resolvable state");
        }
        int outboxUpdated = jdbc.update(
                "update outbox_event set status = 'FAILED', last_error_code = 'ERP_RESULT_FAILED',"
                        + " worker_id = null, claim_token = null, lease_expires_at = null,"
                        + " updated_at = clock_timestamp()"
                        + " where id = ? and status in ('SENDING', 'RESULT_UNKNOWN')",
                context.outboxEventId());
        if (outboxUpdated != 1) {
            throw new PaymentResultConflictException("outbox is not in a resolvable state");
        }
        insertResult(command, context);
        return PaymentResultIngestResult.APPLIED;
    }

    private PaymentResultIngestResult replayOrConflict(RecordedEvent recorded, PaymentResultCommand command) {
        if (recorded.payloadHash().equals(command.payloadHash())) {
            return PaymentResultIngestResult.REPLAY;
        }
        throw new PaymentResultConflictException("event id is already recorded with a different payload");
    }

    private static boolean isResolvable(String status) {
        return status.equals("SENDING") || status.equals("RESULT_UNKNOWN");
    }

    private void insertResult(PaymentResultCommand command, OutboxContext context) {
        jdbc.update(
                "insert into payment_result_event (id, provider, external_event_id, external_payment_key,"
                        + " payment_request_id, outbox_event_id, outcome, payload_hash, external_reference,"
                        + " received_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                command.provider(),
                command.externalEventId(),
                command.externalPaymentKey(),
                context.paymentRequestId(),
                context.outboxEventId(),
                command.outcome().name(),
                command.payloadHash(),
                command.externalReference(),
                Timestamp.from(clock.instant()));
    }

    private OutboxContext loadOutbox(String externalPaymentKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, payment_request_id, invoice_case_id from outbox_event where idempotency_key = ?",
                externalPaymentKey);
        if (rows.isEmpty()) {
            throw new UnknownPaymentKeyException(externalPaymentKey);
        }
        Map<String, Object> row = rows.get(0);
        return new OutboxContext(
                (UUID) row.get("id"), (UUID) row.get("payment_request_id"), (UUID) row.get("invoice_case_id"));
    }

    private void lockInCanonicalOrder(OutboxContext context) {
        jdbc.queryForObject(
                "select id from invoice_case where id = ? for update", UUID.class, context.invoiceCaseId());
        jdbc.queryForList(
                "select status from payment_request where id = ? for update",
                String.class,
                context.paymentRequestId());
        jdbc.queryForList(
                "select status from outbox_event where id = ? for update", String.class, context.outboxEventId());
    }

    private String status(String table, UUID id) {
        return jdbc.queryForObject("select status from " + table + " where id = ?", String.class, id);
    }

    private Optional<RecordedEvent> findRecordedEvent(String provider, String externalEventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select payload_hash from payment_result_event where provider = ? and external_event_id = ?",
                provider,
                externalEventId);
        return rows.stream()
                .findFirst()
                .map(row -> new RecordedEvent((String) row.get("payload_hash")));
    }

    private void lockEventScope(String provider, String externalEventId) {
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, rowNum) -> Boolean.TRUE,
                provider + ":" + externalEventId);
    }

    private record OutboxContext(UUID outboxEventId, UUID paymentRequestId, UUID invoiceCaseId) {
    }

    private record RecordedEvent(String payloadHash) {
    }
}
