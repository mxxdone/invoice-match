package com.invoicematch.core.review.application;

import java.util.UUID;

/**
 * Rejects the invoice claim against the current fresh review snapshot, moving
 * the case to {@code REJECTED}. The actor is derived from the authenticated
 * principal, never from the request.
 */
public record RejectReviewCommand(
        UUID caseId,
        String requestId,
        long expectedCaseVersion,
        UUID reviewSnapshotId,
        String reviewPayloadHash,
        String reason) {
}
