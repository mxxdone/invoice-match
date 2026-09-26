package com.invoicematch.core.invoicecase.application;

/**
 * One manually entered claim line as supplied by a client. It mirrors
 * {@code InvoiceLine} but carries no identity: the write service assigns fresh
 * line ids when it atomically replaces the current draft's line set.
 */
public record InvoiceLineInput(
        int lineNumber, String rawItemName, int quantity, long unitPrice, String confirmedItemId) {
}
