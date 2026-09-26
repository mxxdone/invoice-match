package com.invoicematch.core.review.application;

import com.invoicematch.core.review.domain.ReviewDecision;
import com.invoicematch.core.review.domain.ReviewDecisionType;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of one immutable review decision. Mapping decisions expose their
 * normalized target fields; other decision types leave them null.
 */
public record ReviewDecisionView(
        UUID id,
        UUID invoiceCaseId,
        UUID reviewSnapshotId,
        int decisionNumber,
        ReviewDecisionType decision,
        String decidedBy,
        String reason,
        String payloadHash,
        UUID mappingBundleId,
        Integer mappingLineNumber,
        String mappingItemId,
        String mappingPoLineId,
        Instant decidedAt) {

    public static ReviewDecisionView from(ReviewDecision decision) {
        return new ReviewDecisionView(
                decision.id(),
                decision.invoiceCaseId(),
                decision.reviewSnapshotId(),
                decision.decisionNumber(),
                decision.decision(),
                decision.decidedBy(),
                decision.reason(),
                decision.payloadHash(),
                decision.mappingBundleId(),
                decision.mappingLineNumber(),
                decision.mappingItemId(),
                decision.mappingPoLineId(),
                decision.decidedAt());
    }
}
