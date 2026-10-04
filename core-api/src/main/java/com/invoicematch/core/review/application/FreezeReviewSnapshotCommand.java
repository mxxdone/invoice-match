package com.invoicematch.core.review.application;

import java.util.UUID;

/**
 * Freezes the current review subject for a case: the latest result of the
 * latest frozen bundle computed with the current effective mappings.
 */
public record FreezeReviewSnapshotCommand(UUID caseId, String requestId, long expectedCaseVersion,UUID proposalId,String proposalHash) {
    public FreezeReviewSnapshotCommand(UUID caseId,String requestId,long version) {this(caseId,requestId,version,null,null);}
}
