package com.invoicematch.core.review.application;

import java.util.UUID;

/**
 * Rejects the invoice claim against the current fresh review snapshot, moving
 * the case to {@code REJECTED}.
 */
public record RejectReviewCommand(
        UUID caseId,
        String requestId,
        long expectedCaseVersion,
        UUID reviewSnapshotId,
        String reviewPayloadHash,
        String reason,
        String decidedBy) {
}
