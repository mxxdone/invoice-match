package com.invoicematch.core.invoicecase.application;

import java.util.List;
import java.util.UUID;

/**
 * Command that atomically replaces the line set of the current OPEN draft
 * revision. {@code expectedCaseVersion} guards against lost updates.
 */
public record ReplaceDraftLinesCommand(
        UUID caseId, String requestId, long expectedCaseVersion, List<InvoiceLineInput> lines) {
}
