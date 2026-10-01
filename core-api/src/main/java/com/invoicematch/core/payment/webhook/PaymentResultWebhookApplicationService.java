package com.invoicematch.core.payment.webhook;

import com.invoicematch.core.payment.persistence.PaymentResultEventStore;
import com.invoicematch.core.payment.persistence.PaymentResultEventStore.PaymentExportStatuses;
import com.invoicematch.core.payment.persistence.PaymentResultEventStore.PaymentOutboxContext;
import com.invoicematch.core.payment.persistence.PaymentResultEventStore.PaymentResultEventInsert;
import com.invoicematch.core.payment.persistence.PaymentResultEventStore.RecordedPaymentResultEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent application of a verified external payment result.
 *
 * <p>This is the only P1-09 writer of the payment/outbox/case tuple and it never
 * starts a send. It owns the single transaction boundary and every
 * replay/conflict/state decision; {@link PaymentResultEventStore} owns the SQL
 * and joins this transaction as {@code MANDATORY}. Two writers can race for the
 * same payment (an in-flight relay finalization and an arriving webhook); both
 * take the canonical {@code invoice_case -> payment_request -> outbox_event}
 * locks and both finalizations are conditional, so exactly one wins and the
 * loser becomes a no-op. External HTTP happens before this transaction, never
 * inside it.
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

    private final PaymentResultEventStore store;
    private final Clock clock;

    public PaymentResultWebhookApplicationService(PaymentResultEventStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public PaymentResultIngestResult ingest(PaymentResultCommand command) {
        store.lockEventScope(command.provider(), command.externalEventId());

        Optional<RecordedPaymentResultEvent> recorded =
                store.findRecordedEvent(command.provider(), command.externalEventId());
        if (recorded.isPresent()) {
            return replayOrConflict(recorded.get(), command);
        }

        PaymentOutboxContext context = store.findOutboxContext(command.externalPaymentKey())
                .orElseThrow(() -> new UnknownPaymentKeyException(command.externalPaymentKey()));
        store.lockInCanonicalOrder(context);

        // Re-check under the payment lock: a same-event request cannot pass the
        // advisory lock, but a re-read keeps the decision explicit and cheap.
        recorded = store.findRecordedEvent(command.provider(), command.externalEventId());
        if (recorded.isPresent()) {
            return replayOrConflict(recorded.get(), command);
        }
        if (command.paymentRequestId() != null && !command.paymentRequestId().equals(context.paymentRequestId())) {
            throw new PaymentResultConflictException(
                    "external payment key and payment request id do not match the recorded export");
        }

        PaymentExportStatuses statuses = store.readStatuses(context);
        return command.outcome() == PaymentResultOutcome.ACKNOWLEDGED
                ? applyAcknowledged(command, context, statuses)
                : applyFailed(command, context, statuses);
    }

    private PaymentResultIngestResult applyAcknowledged(
            PaymentResultCommand command, PaymentOutboxContext context, PaymentExportStatuses statuses) {
        if (statuses.paymentStatus().equals("ACKNOWLEDGED") && statuses.outboxStatus().equals("DELIVERED")) {
            insertResult(command, context);
            return PaymentResultIngestResult.REPLAY;
        }
        if (!isResolvable(statuses.paymentStatus()) || !isResolvable(statuses.outboxStatus())) {
            throw new PaymentResultConflictException(
                    "acknowledged result cannot be applied from payment=" + statuses.paymentStatus()
                            + " outbox=" + statuses.outboxStatus());
        }
        int caseUpdated = store.exportCase(context.invoiceCaseId(), clock.instant());
        if (caseUpdated != 1) {
            throw new PaymentResultConflictException("case is not awaiting export");
        }
        int paymentUpdated = store.acknowledgePayment(context.paymentRequestId());
        if (paymentUpdated != 1) {
            throw new PaymentResultConflictException("payment is not in a resolvable state");
        }
        int outboxUpdated = store.acknowledgeOutbox(context.outboxEventId());
        if (outboxUpdated != 1) {
            throw new PaymentResultConflictException("outbox is not in a resolvable state");
        }
        insertResult(command, context);
        return PaymentResultIngestResult.APPLIED;
    }

    private PaymentResultIngestResult applyFailed(
            PaymentResultCommand command, PaymentOutboxContext context, PaymentExportStatuses statuses) {
        if (statuses.paymentStatus().equals("FAILED") && statuses.outboxStatus().equals("FAILED")) {
            insertResult(command, context);
            return PaymentResultIngestResult.REPLAY;
        }
        if (!isResolvable(statuses.paymentStatus()) || !isResolvable(statuses.outboxStatus())) {
            throw new PaymentResultConflictException(
                    "failed result cannot be applied from payment=" + statuses.paymentStatus()
                            + " outbox=" + statuses.outboxStatus());
        }
        int paymentUpdated = store.failPayment(context.paymentRequestId());
        if (paymentUpdated != 1) {
            throw new PaymentResultConflictException("payment is not in a resolvable state");
        }
        int outboxUpdated = store.failOutbox(context.outboxEventId());
        if (outboxUpdated != 1) {
            throw new PaymentResultConflictException("outbox is not in a resolvable state");
        }
        insertResult(command, context);
        return PaymentResultIngestResult.APPLIED;
    }

    private PaymentResultIngestResult replayOrConflict(RecordedPaymentResultEvent recorded, PaymentResultCommand command) {
        if (recorded.payloadHash().equals(command.payloadHash())) {
            return PaymentResultIngestResult.REPLAY;
        }
        throw new PaymentResultConflictException("event id is already recorded with a different payload");
    }

    private static boolean isResolvable(String status) {
        return status.equals("SENDING") || status.equals("RESULT_UNKNOWN");
    }

    private void insertResult(PaymentResultCommand command, PaymentOutboxContext context) {
        store.insertResult(
                new PaymentResultEventInsert(
                        command.provider(),
                        command.externalEventId(),
                        command.externalPaymentKey(),
                        context.paymentRequestId(),
                        context.outboxEventId(),
                        command.outcome().name(),
                        command.payloadHash(),
                        command.externalReference()),
                clock.instant());
    }
}
