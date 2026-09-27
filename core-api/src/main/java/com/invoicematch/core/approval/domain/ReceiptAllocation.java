package com.invoicematch.core.approval.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One append-only consumption of a receipt line's confirmed quantity by one
 * approved invoice line.
 *
 * <p>It is the local source of truth for which claim used a receipt balance
 * (ADR 0002). Every reference is to the same claim: the composite database
 * foreign keys point at the case/purchase order, the approved
 * {@code ReviewDecision} of that case, the {@code ReviewSnapshot} and its
 * evidence bundle, and the receipt line of the same purchase order. The
 * database rejects UPDATE and DELETE and guards the running sum against the
 * confirmed quantity.
 */
@Entity
@Table(name = "receipt_allocation")
@Immutable
public class ReceiptAllocation {

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

    @Column(name = "invoice_line_number", nullable = false, updatable = false)
    private int invoiceLineNumber;

    @Column(name = "receipt_line_snapshot_id", nullable = false, updatable = false)
    private UUID receiptLineSnapshotId;

    @Column(name = "receipt_id", nullable = false, updatable = false, length = 64)
    private String receiptId;

    @Column(name = "receipt_line_id", nullable = false, updatable = false, length = 64)
    private String receiptLineId;

    @Column(name = "purchase_order_line_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderLineId;

    @Column(name = "receipt_line_version", nullable = false, updatable = false)
    private long receiptLineVersion;

    @Column(name = "confirmed_quantity_at_approval", nullable = false, updatable = false)
    private int confirmedQuantityAtApproval;

    @Column(name = "allocated_quantity", nullable = false, updatable = false)
    private int allocatedQuantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReceiptAllocation() {
    }

    private ReceiptAllocation(
            UUID id,
            UUID invoiceCaseId,
            String purchaseOrderId,
            UUID reviewDecisionId,
            UUID reviewSnapshotId,
            UUID evidenceBundleId,
            String reviewPayloadHash,
            int invoiceLineNumber,
            UUID receiptLineSnapshotId,
            String receiptId,
            String receiptLineId,
            String purchaseOrderLineId,
            long receiptLineVersion,
            int confirmedQuantityAtApproval,
            int allocatedQuantity,
            Instant createdAt) {
        if (invoiceLineNumber <= 0) {
            throw new DomainValidationException("invoiceLineNumber must be positive: " + invoiceLineNumber);
        }
        if (receiptLineVersion < 0) {
            throw new DomainValidationException("receiptLineVersion must not be negative: " + receiptLineVersion);
        }
        if (confirmedQuantityAtApproval < 0) {
            throw new DomainValidationException(
                    "confirmedQuantityAtApproval must not be negative: " + confirmedQuantityAtApproval);
        }
        if (allocatedQuantity <= 0) {
            throw new DomainValidationException("allocatedQuantity must be positive: " + allocatedQuantity);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.purchaseOrderId = requireText(purchaseOrderId, "purchaseOrderId");
        this.reviewDecisionId = Objects.requireNonNull(reviewDecisionId, "reviewDecisionId");
        this.reviewSnapshotId = Objects.requireNonNull(reviewSnapshotId, "reviewSnapshotId");
        this.evidenceBundleId = Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        this.reviewPayloadHash = requireText(reviewPayloadHash, "reviewPayloadHash");
        this.invoiceLineNumber = invoiceLineNumber;
        this.receiptLineSnapshotId = Objects.requireNonNull(receiptLineSnapshotId, "receiptLineSnapshotId");
        this.receiptId = requireText(receiptId, "receiptId");
        this.receiptLineId = requireText(receiptLineId, "receiptLineId");
        this.purchaseOrderLineId = requireText(purchaseOrderLineId, "purchaseOrderLineId");
        this.receiptLineVersion = receiptLineVersion;
        this.confirmedQuantityAtApproval = confirmedQuantityAtApproval;
        this.allocatedQuantity = allocatedQuantity;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ReceiptAllocation record(
            UUID id,
            UUID invoiceCaseId,
            String purchaseOrderId,
            UUID reviewDecisionId,
            UUID reviewSnapshotId,
            UUID evidenceBundleId,
            String reviewPayloadHash,
            int invoiceLineNumber,
            UUID receiptLineSnapshotId,
            String receiptId,
            String receiptLineId,
            String purchaseOrderLineId,
            long receiptLineVersion,
            int confirmedQuantityAtApproval,
            int allocatedQuantity,
            Instant createdAt) {
        return new ReceiptAllocation(
                id,
                invoiceCaseId,
                purchaseOrderId,
                reviewDecisionId,
                reviewSnapshotId,
                evidenceBundleId,
                reviewPayloadHash,
                invoiceLineNumber,
                receiptLineSnapshotId,
                receiptId,
                receiptLineId,
                purchaseOrderLineId,
                receiptLineVersion,
                confirmedQuantityAtApproval,
                allocatedQuantity,
                createdAt);
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

    public int invoiceLineNumber() {
        return invoiceLineNumber;
    }

    public UUID receiptLineSnapshotId() {
        return receiptLineSnapshotId;
    }

    public String receiptId() {
        return receiptId;
    }

    public String receiptLineId() {
        return receiptLineId;
    }

    public String purchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public long receiptLineVersion() {
        return receiptLineVersion;
    }

    public int confirmedQuantityAtApproval() {
        return confirmedQuantityAtApproval;
    }

    public int allocatedQuantity() {
        return allocatedQuantity;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReceiptAllocation allocation && Objects.equals(id, allocation.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
