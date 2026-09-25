package com.invoicematch.core.purchasingreference.persistence;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.Quantity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A purchase order line within the current local snapshot. It is replaced
 * together with its parent snapshot and keeps the stable external line id that
 * receipt lines reference.
 */
@Entity
@Table(name = "purchase_order_line_snapshot")
public class PurchaseOrderLineSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderId;

    @Column(name = "purchase_order_line_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderLineId;

    @Column(name = "item_id", nullable = false, length = 64)
    private String itemId;

    @Column(name = "item_name", nullable = false, length = 500)
    private String itemName;

    @Column(name = "ordered_quantity", nullable = false)
    private Quantity orderedQuantity;

    @Column(name = "unit_price", nullable = false)
    private Money unitPrice;

    protected PurchaseOrderLineSnapshot() {
    }

    private PurchaseOrderLineSnapshot(
            UUID id,
            String purchaseOrderId,
            String purchaseOrderLineId,
            String itemId,
            String itemName,
            Quantity orderedQuantity,
            Money unitPrice) {
        this.id = Objects.requireNonNull(id, "id");
        this.purchaseOrderId = requireText(purchaseOrderId, "purchaseOrderId");
        this.purchaseOrderLineId = requireText(purchaseOrderLineId, "purchaseOrderLineId");
        this.itemId = requireText(itemId, "itemId");
        this.itemName = requireText(itemName, "itemName");
        this.orderedQuantity = Objects.requireNonNull(orderedQuantity, "orderedQuantity");
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
    }

    public static PurchaseOrderLineSnapshot create(UUID id, String purchaseOrderId, PurchaseOrderLineFacts facts) {
        return new PurchaseOrderLineSnapshot(
                id,
                purchaseOrderId,
                facts.purchaseOrderLineId(),
                facts.itemId(),
                facts.itemName(),
                facts.orderedQuantity(),
                facts.unitPrice());
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

    public String purchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public String itemId() {
        return itemId;
    }

    public String itemName() {
        return itemName;
    }

    public Quantity orderedQuantity() {
        return orderedQuantity;
    }

    public Money unitPrice() {
        return unitPrice;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PurchaseOrderLineSnapshot line && Objects.equals(id, line.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
