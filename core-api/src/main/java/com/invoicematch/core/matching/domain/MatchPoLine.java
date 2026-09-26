package com.invoicematch.core.matching.domain;

import java.util.Objects;

/**
 * The purchase order line an invoice line was compared against. It carries the
 * exact comparison values so a person can recompute the result.
 */
public record MatchPoLine(
        String purchaseOrderLineId, String itemId, int orderedQuantity, long unitPrice) {

    public MatchPoLine {
        if (purchaseOrderLineId == null || purchaseOrderLineId.isBlank()) {
            throw new IllegalArgumentException("purchaseOrderLineId must not be blank");
        }
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (orderedQuantity <= 0) {
            throw new IllegalArgumentException("orderedQuantity must be positive: " + orderedQuantity);
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative: " + unitPrice);
        }
        Objects.requireNonNull(itemId, "itemId");
    }
}
