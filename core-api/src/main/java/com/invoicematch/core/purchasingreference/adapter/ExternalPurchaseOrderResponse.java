package com.invoicematch.core.purchasingreference.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Wire shape of {@code GET /api/purchase-orders/{id}} on the external
 * purchasing system. Fields are boxed so that a missing required value is
 * distinguishable from a legitimate zero and can be rejected explicitly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ExternalPurchaseOrderResponse(
        Long snapshotVersion,
        ExternalPurchaseOrder purchaseOrder,
        List<ExternalReceipt> receipts) {
}
