package com.invoicematch.core.approval.domain;

import java.util.List;
import java.util.UUID;

/**
 * The frozen review snapshot is current but is not approvable: the match result
 * is abnormal/unresolved or its expected allocation plan is missing or does not
 * cover the invoice quantities. No side effect is applied. The reasons explain
 * why the snapshot cannot be approved.
 */
public class ApprovalNotPermittedException extends RuntimeException {

    private final UUID caseId;
    private final UUID reviewSnapshotId;
    private final List<String> reasons;

    public ApprovalNotPermittedException(UUID caseId, UUID reviewSnapshotId, List<String> reasons) {
        super("Review snapshot " + reviewSnapshotId + " of case " + caseId + " is not approvable: " + reasons);
        this.caseId = caseId;
        this.reviewSnapshotId = reviewSnapshotId;
        this.reasons = List.copyOf(reasons);
    }

    public UUID caseId() {
        return caseId;
    }

    public UUID reviewSnapshotId() {
        return reviewSnapshotId;
    }

    public List<String> reasons() {
        return reasons;
    }
}
