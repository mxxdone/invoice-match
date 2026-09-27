package com.invoicematch.core.approval.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stable result of one approval. It is stored as the idempotency response body,
 * so a replay returns the same decision, allocation set, payment identity and
 * case state without repeating any side effect.
 */
public record ApprovalResult(
        UUID invoiceCaseId,
        String status,
        long caseVersion,
        UUID reviewDecisionId,
        int decisionNumber,
        UUID reviewSnapshotId,
        String reviewPayloadHash,
        UUID paymentRequestId,
        String externalRequestKey,
        long amount,
        String currency,
        List<ApprovedAllocation> allocations,
        Instant approvedAt) {

    public ApprovalResult {
        allocations = List.copyOf(allocations);
    }
}
