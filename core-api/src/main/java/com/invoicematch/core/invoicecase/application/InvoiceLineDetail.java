package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.InvoiceLine;

/**
 * A claim line as exposed over the API.
 */
public record InvoiceLineDetail(
        int lineNumber, String rawItemName, int quantity, long unitPrice, String confirmedItemId) {

    public static InvoiceLineDetail from(InvoiceLine line) {
        return new InvoiceLineDetail(
                line.lineNumber(),
                line.rawItemName(),
                line.quantity().value(),
                line.unitPrice().amount(),
                line.confirmedItemId());
    }
}
