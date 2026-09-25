package com.invoicematch.core.purchasingreference.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record ExternalReceiptLine(
        String receiptLineId,
        Long version,
        String purchaseOrderLineId,
        Integer confirmedQuantity) {
}
