package com.invoicematch.core.review.domain;

import java.util.List;
import java.util.UUID;

/**
 * A human action targeted a review snapshot that is not the current, fresh
 * review subject. The reasons are returned so the caller can explain exactly
 * which input changed. No side effect is applied.
 */
public class StaleReviewTargetException extends RuntimeException {

    private final List<StaleReason> reasons;

    public StaleReviewTargetException(UUID caseId, UUID snapshotId, List<StaleReason> reasons) {
        super("Review snapshot " + snapshotId + " of case " + caseId + " is stale: " + reasons);
        this.reasons = List.copyOf(reasons);
    }

    public List<StaleReason> reasons() {
        return reasons;
    }
}
