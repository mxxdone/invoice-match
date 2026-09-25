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
 * allocation quantity.
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

    @Column(name = "external_version", nullable = false)
    private long externalVersion;

    @Column(name = "confirmed_quantity", nullable = false)
    private ConfirmedQuantity confirmedQuantity;

    protected ReceiptLineSnapshot() {
    }

    private ReceiptLineSnapshot(
            UUID id,
            String purchaseOrderId,
            String receiptId,
            String receiptLineId,
            String purchaseOrderLineId,
            long externalVersion,
            ConfirmedQuantity confirmedQuantity) {
        if (externalVersion < 0) {
            throw new DomainValidationException("externalVersion must not be negative: " + externalVersion);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.purchaseOrderId = requireText(purchaseOrderId, "purchaseOrderId");
        this.receiptId = requireText(receiptId, "receiptId");
        this.receiptLineId = requireText(receiptLineId, "receiptLineId");
        this.purchaseOrderLineId = requireText(purchaseOrderLineId, "purchaseOrderLineId");
        this.externalVersion = externalVersion;
        this.confirmedQuantity = Objects.requireNonNull(confirmedQuantity, "confirmedQuantity");
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
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

    public long externalVersion() {
        return externalVersion;
    }

    public ConfirmedQuantity confirmedQuantity() {
        return confirmedQuantity;
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
