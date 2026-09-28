package com.invoicematch.core.payment.domain;

import com.invoicematch.core.approval.domain.PaymentRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional outbox row for one payment export. The approval transaction
 * inserts exactly one row together with the PaymentRequest, so the committed
 * business state and the pending hand-off are never separated.
 *
 * <p>Subject, payload, hash, key and version are immutable (V8 trigger); only
 * the delivery state, lease and attempt metadata change. The relay updates the
 * row with claim-token compare-and-set statements, not through this entity.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "payment_request_id", nullable = false, updatable = false)
    private UUID paymentRequestId;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "export_version", nullable = false, updatable = false)
    private long exportVersion;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxEventStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected OutboxEvent() {
    }

    /**
     * Builds the ready-to-send event for an approved payment. The canonical
     * payload and hash are derived here from the immutable PaymentRequest and
     * re-validated by the V8 insert trigger.
     */
    public static OutboxEvent exportRequested(PaymentRequest paymentRequest, Instant now) {
        Objects.requireNonNull(paymentRequest, "paymentRequest");
        Objects.requireNonNull(now, "now");
        PaymentExportPayload canonical = PaymentExportPayload.forPaymentRequest(paymentRequest);
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.aggregateType = "PAYMENT_REQUEST";
        event.aggregateId = paymentRequest.id();
        event.paymentRequestId = paymentRequest.id();
        event.invoiceCaseId = paymentRequest.invoiceCaseId();
        event.eventType = PaymentExportPayload.EVENT_TYPE;
        event.exportVersion = paymentRequest.exportVersion();
        event.idempotencyKey = canonical.idempotencyKey();
        event.payload = canonical.canonicalJson();
        event.payloadHash = canonical.payloadHash();
        event.status = OutboxEventStatus.READY;
        event.attemptCount = 0;
        event.createdAt = now;
        event.updatedAt = now;
        return event;
    }

    public UUID id() {
        return id;
    }

    public UUID paymentRequestId() {
        return paymentRequestId;
    }

    public UUID invoiceCaseId() {
        return invoiceCaseId;
    }

    public String eventType() {
        return eventType;
    }

    public long exportVersion() {
        return exportVersion;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String payload() {
        return payload;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public OutboxEventStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String workerId() {
        return workerId;
    }

    public UUID claimToken() {
        return claimToken;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboxEvent event && Objects.equals(id, event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
