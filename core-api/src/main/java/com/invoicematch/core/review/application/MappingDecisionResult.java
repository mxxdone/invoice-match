package com.invoicematch.core.review.application;

/**
 * The outcome of a mapping decision: the append-only decision that was recorded
 * and the successor review snapshot frozen from the deterministic re-match that
 * applied every effective mapping.
 */
public record MappingDecisionResult(ReviewDecisionView decision, ReviewSnapshotView successorSnapshot) {
}
