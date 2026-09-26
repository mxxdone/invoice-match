package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import java.util.UUID;

/**
 * Result of submitting a claim: the case advanced to {@code REVIEW_PENDING}
 * and the current draft was frozen into a specific evidence bundle version.
 */
public record SubmissionResult(
        UUID caseId, InvoiceCaseStatus status, long version, EvidenceBundleSummary evidenceBundle) {
}
