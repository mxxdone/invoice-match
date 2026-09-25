package com.invoicematch.core.invoicecase.domain;

/**
 * Thrown when an {@link InvoiceCase} is asked to move to a status that the
 * confirmed Phase 1 state contract does not allow.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(InvoiceCaseId caseId, InvoiceCaseStatus from, InvoiceCaseStatus to) {
        super("InvoiceCase " + caseId + " cannot transition from " + from + " to " + to);
    }
}
