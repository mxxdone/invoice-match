package com.invoicematch.core.review.application;

import java.util.UUID;

/**
 * Confirms a supplement request against the current fresh review snapshot,
 * moving the case to {@code SUPPLEMENT_REQUIRED}.
 */
public record RequestSupplementCommand(
        UUID caseId,
        String requestId,
        long expectedCaseVersion,
        UUID reviewSnapshotId,
        String reviewPayloadHash,
        String reason,
        String decidedBy) {
}
