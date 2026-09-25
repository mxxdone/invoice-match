package com.invoicematch.core.invoicecase.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root that ties one supplier and exactly one purchase order to the
 * evidence, matching results and human decisions of a single claim.
 *
 * <p>State changes go through {@link #transitionTo(InvoiceCaseStatus, Instant)}
 * so that only transitions in the confirmed Phase 1 contract are accepted. The
 * {@code version} column provides optimistic locking for concurrent edits.
 */
@Entity
@Table(name = "invoice_case")
public class InvoiceCase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "supplier_id", nullable = false, updatable = false, length = 64)
    private String supplierId;

    @Column(name = "purchase_order_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderId;

    @Column(name = "invoice_number", nullable = false, updatable = false, length = 100)
    private String invoiceNumber;

    @Column(name = "normalized_invoice_number", nullable = false, updatable = false, length = 100)
    private String normalizedInvoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InvoiceCaseStatus status;

    @Column(name = "current_draft_revision_id")
    private UUID currentDraftRevisionId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected InvoiceCase() {
    }

    private InvoiceCase(
            InvoiceCaseId id,
            SupplierId supplierId,
            PurchaseOrderId purchaseOrderId,
            String invoiceNumber,
            String normalizedInvoiceNumber,
            Instant now) {
        this.id = id.value();
        this.supplierId = supplierId.value();
        this.purchaseOrderId = purchaseOrderId.value();
        this.invoiceNumber = requireText(invoiceNumber, "invoiceNumber");
        this.normalizedInvoiceNumber = requireText(normalizedInvoiceNumber, "normalizedInvoiceNumber");
        this.status = InvoiceCaseStatus.DRAFT;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Creates a claim case in {@link InvoiceCaseStatus#DRAFT}.
     */
    public static InvoiceCase create(
            InvoiceCaseId id,
            SupplierId supplierId,
            PurchaseOrderId purchaseOrderId,
            String invoiceNumber,
            String normalizedInvoiceNumber,
            Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(supplierId, "supplierId");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
        return new InvoiceCase(id, supplierId, purchaseOrderId, invoiceNumber, normalizedInvoiceNumber, now);
    }

    /**
     * Moves the case to {@code target} when the Phase 1 state contract allows
     * it, stamping {@code occurredAt} on the transition.
     */
    public void transitionTo(InvoiceCaseStatus target, Instant occurredAt) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(id(), status, target);
        }
        this.status = target;
        this.updatedAt = occurredAt;
        if (target == InvoiceCaseStatus.SUBMITTED) {
            this.submittedAt = occurredAt;
        }
    }

    /**
     * Points the case at the draft revision currently being edited. Sealing the
     * revision and freezing it into an {@code EvidenceBundle} belongs to the
     * submission ticket.
     */
    public void attachDraftRevision(UUID draftRevisionId, Instant occurredAt) {
        this.currentDraftRevisionId = Objects.requireNonNull(draftRevisionId, "draftRevisionId");
        this.updatedAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public InvoiceCaseId id() {
        return InvoiceCaseId.of(id);
    }

    public SupplierId supplier() {
        return SupplierId.of(supplierId);
    }

    public PurchaseOrderId purchaseOrder() {
        return PurchaseOrderId.of(purchaseOrderId);
    }

    public String invoiceNumber() {
        return invoiceNumber;
    }

    public String normalizedInvoiceNumber() {
        return normalizedInvoiceNumber;
    }

    public InvoiceCaseStatus status() {
        return status;
    }

    public UUID currentDraftRevisionId() {
        return currentDraftRevisionId;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InvoiceCase invoiceCase && Objects.equals(id, invoiceCase.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
