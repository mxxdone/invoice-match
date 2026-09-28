package com.invoicematch.core.approval.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The internal ERP hand-off unit created by an approval: exactly one record per
 * approved review snapshot, carrying the frozen amount, currency, claim,
 * decision and snapshot.
 *
 * <p>The deterministic {@code externalRequestKey} is globally unique (Spec
 * invariant 6) and the {@code (case, snapshot)} uniqueness is the database
 * backstop that a second approval of the same snapshot can never create a second
 * logical payment. P1-08 adds the {@code exportVersion} and the hand-off state
 * machine; the outbox relay drives the status via short, claim-token-guarded
 * transactions.
 */
@Entity
@Table(name = "payment_request")
public class PaymentRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "purchase_order_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderId;

    @Column(name = "review_decision_id", nullable = false, updatable = false)
    private UUID reviewDecisionId;

    @Column(name = "review_snapshot_id", nullable = false, updatable = false)
    private UUID reviewSnapshotId;

    @Column(name = "evidence_bundle_id", nullable = false, updatable = false)
    private UUID evidenceBundleId;

    @Column(name = "review_payload_hash", nullable = false, updatable = false, length = 128)
    private String reviewPayloadHash;

    @Column(name = "external_request_key", nullable = false, updatable = false, length = 200)
    private String externalRequestKey;

    @Column(name = "amount", nullable = false, updatable = false)
    private Money amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /** Export line version; the ERP idempotency key is id + ":" + version (Spec 14.2). */
    @Column(name = "export_version", nullable = false, updatable = false)
    private long exportVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentRequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** P1-08 hand-off version; P1-07 always emits the first export. */
    public static final long INITIAL_EXPORT_VERSION = 1L;

    protected PaymentRequest() {
    }

    private PaymentRequest(
            UUID id,
            UUID invoiceCaseId,
            String purchaseOrderId,
            UUID reviewDecisionId,
            UUID reviewSnapshotId,
            UUID evidenceBundleId,
            String reviewPayloadHash,
            String externalRequestKey,
            Money amount,
            String currency,
            long exportVersion,
            PaymentRequestStatus status,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.purchaseOrderId = requireText(purchaseOrderId, "purchaseOrderId");
        this.reviewDecisionId = Objects.requireNonNull(reviewDecisionId, "reviewDecisionId");
        this.reviewSnapshotId = Objects.requireNonNull(reviewSnapshotId, "reviewSnapshotId");
        this.evidenceBundleId = Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        this.reviewPayloadHash = requireText(reviewPayloadHash, "reviewPayloadHash");
        this.externalRequestKey = requireText(externalRequestKey, "externalRequestKey");
        this.amount = Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new DomainValidationException("PaymentRequest amount must be positive");
        }
        this.currency = requireText(currency, "currency");
        if (exportVersion != INITIAL_EXPORT_VERSION) {
            throw new DomainValidationException("PaymentRequest exportVersion must be 1");
        }
        this.exportVersion = exportVersion;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (!this.externalRequestKey.equals(externalRequestKey(invoiceCaseId, reviewSnapshotId))) {
            throw new DomainValidationException(
                    "PaymentRequest externalRequestKey must be the deterministic case/snapshot key");
        }
    }

    public static PaymentRequest notSent(
            UUID id,
            UUID invoiceCaseId,
            String purchaseOrderId,
            UUID reviewDecisionId,
            UUID reviewSnapshotId,
            UUID evidenceBundleId,
            String reviewPayloadHash,
            String externalRequestKey,
            Money amount,
            String currency,
            Instant createdAt) {
        return new PaymentRequest(
                id,
                invoiceCaseId,
                purchaseOrderId,
                reviewDecisionId,
                reviewSnapshotId,
                evidenceBundleId,
                reviewPayloadHash,
                externalRequestKey,
                amount,
                currency,
                INITIAL_EXPORT_VERSION,
                PaymentRequestStatus.NOT_SENT,
                createdAt);
    }

    /**
     * Moves the export status along the P1-08 state machine. The database
     * trigger repeats this check so raw SQL cannot bypass it.
     */
    public void transitionTo(PaymentRequestStatus target) {
        Objects.requireNonNull(target, "target");
        if (!status.canTransitionTo(target)) {
            throw new DomainValidationException("illegal PaymentRequest transition " + status + " -> " + target);
        }
        this.status = target;
    }

    /** ERP idempotency key: {@code paymentRequestId + exportVersion} (Spec 14.2). */
    public String exportIdempotencyKey() {
        return id + ":" + exportVersion;
    }

    /**
     * Deterministic external request key. The snapshot is unique per approval,
     * so this key is stable across a retry and distinct across approvals.
     */
    public static String externalRequestKey(UUID invoiceCaseId, UUID reviewSnapshotId) {
        return "PAYMENT:" + invoiceCaseId + ":" + reviewSnapshotId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public UUID invoiceCaseId() {
        return invoiceCaseId;
    }

    public String purchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID reviewDecisionId() {
        return reviewDecisionId;
    }

    public UUID reviewSnapshotId() {
        return reviewSnapshotId;
    }

    public UUID evidenceBundleId() {
        return evidenceBundleId;
    }

    public String reviewPayloadHash() {
        return reviewPayloadHash;
    }

    public String externalRequestKey() {
        return externalRequestKey;
    }

    public Money amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public long exportVersion() {
        return exportVersion;
    }

    public PaymentRequestStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PaymentRequest payment && Objects.equals(id, payment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
