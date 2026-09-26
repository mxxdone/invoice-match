package com.invoicematch.core.review.domain;

import java.util.UUID;

/**
 * The requested review snapshot does not exist for the case.
 */
public class ReviewSnapshotNotFoundException extends RuntimeException {

    public ReviewSnapshotNotFoundException(UUID caseId, UUID snapshotId) {
        super("Invoice case " + caseId + " has no review snapshot " + snapshotId);
    }

    public ReviewSnapshotNotFoundException(UUID caseId) {
        super("Invoice case " + caseId + " has no review snapshot");
    }
}
