package com.invoicematch.core.payment.application;

import com.invoicematch.core.approval.domain.PaymentRequest;
import com.invoicematch.core.payment.domain.OutboxEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only hand-off state of one approved payment: the frozen payment identity
 * and amount plus the outbox delivery state the ERP relay drives. Both the
 * payment status and the outbox status are returned verbatim so the UI never
 * re-derives the export state from individual facts.
 */
public record PaymentHandoffView(
        UUID invoiceCaseId,
        UUID paymentRequestId,
        String externalRequestKey,
        long amount,
        String currency,
        long exportVersion,
        String paymentStatus,
        String outboxStatus,
        int attemptCount,
        String lastErrorCode,
        Instant nextAttemptAt,
        Instant deliveredAt,
        Instant createdAt) {

    public static PaymentHandoffView from(PaymentRequest payment, OutboxEvent outbox) {
        return new PaymentHandoffView(
                payment.invoiceCaseId(),
                payment.id(),
                payment.externalRequestKey(),
                payment.amount().amount(),
                payment.currency(),
                payment.exportVersion(),
                payment.status().name(),
                outbox == null ? null : outbox.status().name(),
                outbox == null ? 0 : outbox.attemptCount(),
                outbox == null ? null : outbox.lastErrorCode(),
                outbox == null ? null : outbox.nextAttemptAt(),
                outbox == null ? null : outbox.deliveredAt(),
                payment.createdAt());
    }
}
