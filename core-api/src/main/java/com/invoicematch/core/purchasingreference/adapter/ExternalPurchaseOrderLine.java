package com.invoicematch.core.purchasingreference.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record ExternalPurchaseOrderLine(
        String purchaseOrderLineId,
        String itemId,
        String itemName,
        Integer orderedQuantity,
        Long unitPrice) {
}
