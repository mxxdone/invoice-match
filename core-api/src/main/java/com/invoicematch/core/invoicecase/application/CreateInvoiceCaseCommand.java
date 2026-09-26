package com.invoicematch.core.invoicecase.application;

/**
 * Command to create one invoice case. The supplier and purchase order are the
 * case header identity and never change afterwards; the invoice number is the
 * supplier claim number used for duplicate detection.
 */
public record CreateInvoiceCaseCommand(
        String requestId, String supplierId, String purchaseOrderId, String invoiceNumber) {
}
