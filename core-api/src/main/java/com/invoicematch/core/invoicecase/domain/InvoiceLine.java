package com.invoicematch.core.invoicecase.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.Quantity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A claim line as written on the supplier invoice. Lines belong to a draft
 * revision until that revision is sealed and frozen into an evidence bundle.
 */
@Entity
@Table(name = "invoice_line")
public class InvoiceLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "draft_revision_id", nullable = false, updatable = false)
    private UUID draftRevisionId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "raw_item_name", nullable = false, length = 500)
    private String rawItemName;

    @Column(name = "quantity", nullable = false)
    private Quantity quantity;

    @Column(name = "unit_price", nullable = false)
    private Money unitPrice;

    @Column(name = "confirmed_item_id", length = 64)
    private String confirmedItemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvoiceLine() {
    }

    private InvoiceLine(
            UUID id,
            UUID invoiceCaseId,
            UUID draftRevisionId,
            int lineNumber,
            String rawItemName,
            Quantity quantity,
            Money unitPrice,
            String confirmedItemId,
            Instant now) {
        if (lineNumber <= 0) {
            throw new DomainValidationException("Line number must be positive: " + lineNumber);
        }
        if (rawItemName == null || rawItemName.isBlank()) {
            throw new DomainValidationException("rawItemName must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.draftRevisionId = Objects.requireNonNull(draftRevisionId, "draftRevisionId");
        this.lineNumber = lineNumber;
        this.rawItemName = rawItemName;
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
        this.confirmedItemId = confirmedItemId;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public static InvoiceLine create(
            UUID id,
            UUID invoiceCaseId,
            UUID draftRevisionId,
            int lineNumber,
            String rawItemName,
            Quantity quantity,
            Money unitPrice,
            String confirmedItemId,
            Instant now) {
        return new InvoiceLine(
                id, invoiceCaseId, draftRevisionId, lineNumber, rawItemName, quantity, unitPrice, confirmedItemId, now);
    }

    /**
     * Total for this line, computed with overflow protection.
     */
    public Money lineTotal() {
        return unitPrice.multiply(quantity);
    }

    public UUID id() {
        return id;
    }

    public UUID invoiceCaseId() {
        return invoiceCaseId;
    }

    public UUID draftRevisionId() {
        return draftRevisionId;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public String rawItemName() {
        return rawItemName;
    }

    public Quantity quantity() {
        return quantity;
    }

    public Money unitPrice() {
        return unitPrice;
    }

    public String confirmedItemId() {
        return confirmedItemId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InvoiceLine line && Objects.equals(id, line.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
