package com.invoicematch.core.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One append-only piece of evidence explaining a send outcome. It carries no
 * request/response body and no credential: only bounded codes, the HTTP status
 * and the claim/attempt identity. UPDATE and DELETE are rejected by V8, so the
 * history of every delivery decision is durable.
 */
@Entity
@Table(name = "outbox_delivery_attempt")
@Immutable
public class OutboxDeliveryAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "outbox_event_id", nullable = false, updatable = false)
    private UUID outboxEventId;

    @Column(name = "payment_request_id", nullable = false, updatable = false)
    private UUID paymentRequestId;

    @Column(name = "claim_token", nullable = false, updatable = false)
    private UUID claimToken;

    @Column(name = "worker_id", nullable = false, updatable = false, length = 128)
    private String workerId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 32)
    private DeliveryAttemptOutcome outcome;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "error_code", updatable = false, length = 64)
    private String errorCode;

    @Column(name = "detail", updatable = false, length = 500)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected OutboxDeliveryAttempt() {
    }

    public static OutboxDeliveryAttempt record(
            UUID outboxEventId,
            UUID paymentRequestId,
            UUID claimToken,
            String workerId,
            int attemptNumber,
            DeliveryAttemptOutcome outcome,
            Integer httpStatus,
            String errorCode,
            String detail,
            Instant occurredAt) {
        OutboxDeliveryAttempt attempt = new OutboxDeliveryAttempt();
        attempt.id = UUID.randomUUID();
        attempt.outboxEventId = Objects.requireNonNull(outboxEventId, "outboxEventId");
        attempt.paymentRequestId = Objects.requireNonNull(paymentRequestId, "paymentRequestId");
        attempt.claimToken = Objects.requireNonNull(claimToken, "claimToken");
        attempt.workerId = Objects.requireNonNull(workerId, "workerId");
        attempt.attemptNumber = attemptNumber;
        attempt.outcome = Objects.requireNonNull(outcome, "outcome");
        attempt.httpStatus = httpStatus;
        attempt.errorCode = errorCode == null ? null : errorCode.substring(0, Math.min(errorCode.length(), 64));
        attempt.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        attempt.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        return attempt;
    }

    public UUID id() {
        return id;
    }

    public UUID outboxEventId() {
        return outboxEventId;
    }

    public UUID paymentRequestId() {
        return paymentRequestId;
    }

    public UUID claimToken() {
        return claimToken;
    }

    public String workerId() {
        return workerId;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public DeliveryAttemptOutcome outcome() {
        return outcome;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
