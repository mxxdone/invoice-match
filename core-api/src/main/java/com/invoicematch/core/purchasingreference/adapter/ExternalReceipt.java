package com.invoicematch.core.purchasingreference.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record ExternalReceipt(
        String receiptId,
        String status,
        LocalDate receiptDate,
        Long version,
        List<ExternalReceiptLine> lines) {
}
