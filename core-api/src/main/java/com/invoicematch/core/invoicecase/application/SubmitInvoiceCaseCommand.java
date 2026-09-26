package com.invoicematch.core.invoicecase.application;

import java.util.UUID;

/**
 * Command to freeze the current OPEN draft revision into the next immutable
 * evidence bundle version and move the case to {@code REVIEW_PENDING}.
 */
public record SubmitInvoiceCaseCommand(UUID caseId, String requestId, long expectedCaseVersion) {
}
