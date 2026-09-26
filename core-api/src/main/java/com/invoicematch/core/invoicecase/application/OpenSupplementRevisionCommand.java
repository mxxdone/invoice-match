package com.invoicematch.core.invoicecase.application;

import java.util.UUID;

/**
 * Command to open the next editable draft revision after a supplement request.
 * It is only valid while the case is {@code SUPPLEMENT_REQUIRED}, and it copies
 * the previously frozen lines as the editable starting point.
 */
public record OpenSupplementRevisionCommand(UUID caseId, String requestId, long expectedCaseVersion) {
}
