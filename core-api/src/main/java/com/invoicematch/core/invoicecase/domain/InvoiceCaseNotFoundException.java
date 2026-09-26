package com.invoicematch.core.invoicecase.domain;

import java.util.UUID;

/**
 * The requested invoice case does not exist.
 */
public class InvoiceCaseNotFoundException extends RuntimeException {

    public InvoiceCaseNotFoundException(UUID caseId) {
        super("Invoice case not found: " + caseId);
    }
}
