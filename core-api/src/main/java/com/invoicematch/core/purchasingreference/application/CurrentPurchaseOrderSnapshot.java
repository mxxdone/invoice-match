package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;

/**
 * A current purchasing snapshot together with the canonical payload hash the
 * store persisted for it. Matching keeps this hash as calculation evidence so a
 * persisted result can be tied to the exact snapshot version it compared.
 */
public record CurrentPurchaseOrderSnapshot(String payloadHash, PurchaseOrderAggregate aggregate) {

    public CurrentPurchaseOrderSnapshot {
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("payloadHash must not be blank");
        }
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
    }
}
