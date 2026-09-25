package com.invoicematch.core.purchasingreference.persistence;

import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import com.invoicematch.core.shared.domain.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A receipt line within the current local snapshot. {@code confirmedQuantity}
 * is the externally confirmed quantity and may be zero; it is not the local
 * allocation quantity. The row is keyed by the stable external receipt line id
 * and deactivated instead of deleted when it leaves a newer snapshot.
 */
@Entity
@Table(name = "receipt_line_snapshot")
public class ReceiptLineSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderId;

    @Column(name = "receipt_id", nullable = false, updatable = false, length = 64)
    private String receiptId;

    @Column(name = "receipt_line_id", nullable = false, updatable = false, length = 64)
    private String receiptLineId;

    @Column(name = "purchase_order_line_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderLineId;

    @Column(name = "receipt_line_version", nullable = false)
    private long receiptLineVersion;

    @Column(name = "confirmed_quantity", nullable = false)
    private ConfirmedQuantity confirmedQuantity;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ReceiptLineSnapshot() {
    }

    private ReceiptLineSnapshot(
            UUID id,
            String purchaseOrderId,
            String receiptId,
            String receiptLineId,
            String purchaseOrderLineId,
            long receiptLineVersion,
            ConfirmedQuantity confirmedQuantity) {
        this.id = Objects.requireNonNull(id, "id");
        this.purchaseOrderId = requireText(purchaseOrderId, "purchaseOrderId");
        this.receiptId = requireText(receiptId, "receiptId");
        this.receiptLineId = requireText(receiptLineId, "receiptLineId");
        this.purchaseOrderLineId = requireText(purchaseOrderLineId, "purchaseOrderLineId");
        this.receiptLineVersion = requireNonNegative(receiptLineVersion, "receiptLineVersion");
        this.confirmedQuantity = Objects.requireNonNull(confirmedQuantity, "confirmedQuantity");
        this.active = true;
    }

    public static ReceiptLineSnapshot create(
            UUID id, String purchaseOrderId, String receiptId, ReceiptLineFacts facts) {
        return new ReceiptLineSnapshot(
                id,
                purchaseOrderId,
                receiptId,
                facts.receiptLineId(),
                facts.purchaseOrderLineId(),
                facts.version(),
                facts.confirmedQuantity());
    }

    /**
     * Updates the mutable facts of this receipt line and marks it active again.
     * The row id and external receipt line id are never changed.
     */
    public void updateFrom(ReceiptLineFacts facts) {
        this.receiptLineVersion = requireNonNegative(facts.version(), "receiptLineVersion");
        this.confirmedQuantity = Objects.requireNonNull(facts.confirmedQuantity(), "confirmedQuantity");
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        return value;
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new DomainValidationException(field + " must not be negative: " + value);
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public String purchaseOrderId() {
        return purchaseOrderId;
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

    public ConfirmedQuantity confirmedQuantity() {
        return confirmedQuantity;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReceiptLineSnapshot line && Objects.equals(id, line.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
