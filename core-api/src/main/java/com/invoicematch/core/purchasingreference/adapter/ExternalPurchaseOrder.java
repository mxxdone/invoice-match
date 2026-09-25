package com.invoicematch.core.purchasingreference.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record ExternalPurchaseOrder(
        String purchaseOrderId,
        String status,
        Long version,
        ExternalSupplier supplier,
        List<ExternalPurchaseOrderLine> lines) {
}
