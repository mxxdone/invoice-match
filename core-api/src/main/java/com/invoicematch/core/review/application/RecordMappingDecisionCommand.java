package com.invoicematch.core.review.application;

import java.util.UUID;

/**
 * Records a human item mapping against one invoice line of the exact review
 * snapshot the person saw, then re-matches and freezes a successor snapshot.
 */
public record RecordMappingDecisionCommand(
        UUID caseId,
        String requestId,
        long expectedCaseVersion,
        UUID reviewSnapshotId,
        String reviewPayloadHash,
        int lineNumber,
        String itemId,
        String decidedBy) {
}
