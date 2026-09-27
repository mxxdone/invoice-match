package com.invoicematch.core.approval.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Exact-subject approval command: the authenticated approver approves the review
 * snapshot they were shown ({@code reviewSnapshotId} + {@code reviewPayloadHash})
 * at the case version they saw.
 */
public record ApproveInvoiceCaseCommand(
        UUID caseId,
        String requestId,
        long expectedCaseVersion,
        UUID reviewSnapshotId,
        String reviewPayloadHash) {

    public ApproveInvoiceCaseCommand {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(reviewSnapshotId, "reviewSnapshotId");
        Objects.requireNonNull(reviewPayloadHash, "reviewPayloadHash");
    }
}
