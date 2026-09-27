package com.invoicematch.core.approval.application;

import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.review.domain.ReviewSnapshot;

/**
 * The approval subject reconstructed and verified from authoritative relational
 * sources inside the locked approval transaction. Nothing here comes from the
 * stored snapshot/match/bundle JSON as authority; the stored hashes are only
 * compared against independent recomputation.
 */
public record VerifiedApprovalSubject(
        EvidenceBundle evidenceBundle,
        MatchResult matchResult,
        ReviewSnapshot reviewSnapshot,
        long purchasingSnapshotVersion,
        String purchasingSnapshotHash,
        ApprovedAllocationPlan plan) {
}
