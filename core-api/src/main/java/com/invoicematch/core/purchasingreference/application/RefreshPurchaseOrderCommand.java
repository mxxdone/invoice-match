package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import java.util.Objects;

/**
 * Asks to refresh the local snapshot of one purchase order. The caller states
 * the expected supplier identity so a mismatch between the requested case and
 * the external aggregate is detected instead of silently accepted.
 */
public record RefreshPurchaseOrderCommand(PurchaseOrderId purchaseOrderId, SupplierId expectedSupplierId) {

    public RefreshPurchaseOrderCommand {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
        Objects.requireNonNull(expectedSupplierId, "expectedSupplierId");
    }
}
