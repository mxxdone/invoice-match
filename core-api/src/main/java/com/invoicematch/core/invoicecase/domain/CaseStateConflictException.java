package com.invoicematch.core.invoicecase.domain;

import java.util.UUID;

/**
 * Thrown when an operation is not allowed for the current case status, such as
 * opening the next revision before the case is {@code SUPPLEMENT_REQUIRED}.
 */
public class CaseStateConflictException extends RuntimeException {

    public CaseStateConflictException(UUID caseId, String message) {
        super("Invoice case " + caseId + ": " + message);
    }
}
