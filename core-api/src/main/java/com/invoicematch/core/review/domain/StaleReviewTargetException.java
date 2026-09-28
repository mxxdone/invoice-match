package com.invoicematch.core.review.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A human action targeted a review snapshot that is not the current, fresh
 * review subject. The reasons are returned so the caller can explain exactly
 * which input changed. No side effect is applied.
 *
 * <p>The current case version and status are captured at the moment the target
 * was found stale, so the 409 body can carry a machine-readable latest case
 * identifier without re-reading the conflict.
 */
public class StaleReviewTargetException extends RuntimeException {

    private final List<StaleReason> reasons;
    private final long currentCaseVersion;
    private final String currentCaseStatus;

    public StaleReviewTargetException(
            UUID caseId,
            UUID snapshotId,
            List<StaleReason> reasons,
            long currentCaseVersion,
            String currentCaseStatus) {
        super("Review snapshot " + snapshotId + " of case " + caseId + " is stale: " + reasons);
        this.reasons = List.copyOf(reasons);
        this.currentCaseVersion = currentCaseVersion;
        this.currentCaseStatus = Objects.requireNonNull(currentCaseStatus, "currentCaseStatus");
    }

    public List<StaleReason> reasons() {
        return reasons;
    }

    public long currentCaseVersion() {
        return currentCaseVersion;
    }

    public String currentCaseStatus() {
        return currentCaseStatus;
    }
}
