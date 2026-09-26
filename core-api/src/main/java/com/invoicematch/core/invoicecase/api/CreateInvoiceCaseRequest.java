package com.invoicematch.core.invoicecase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateInvoiceCaseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String supplierId,
        @NotBlank @Size(max = 64) String purchaseOrderId,
        @NotBlank @Size(max = 100) String invoiceNumber) {
}
