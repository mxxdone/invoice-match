package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import com.invoicematch.core.document.domain.DocumentEvidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The disposition of a claim. Only {@link Claimed} carries a token and the
 * authoritative manifest; the other outcomes deliberately expose no manifest.
 */
public sealed interface ClaimOutcome {

    /** A fresh or reclaimed execution. */
    record Claimed(
            UUID claimToken,
            Instant leaseUntil,
            UUID invoiceCaseId,
            UUID evidenceBundleId,
            int inputVersion,
            String evidencePayloadHash,
            String workflowVersion,
            List<DocumentEvidence> documents) implements ClaimOutcome {
    }

    /** Another worker holds an unexpired claim on the same run. */
    record Busy(Instant leaseUntil) implements ClaimOutcome {
    }

    /** The run already reached a terminal COMPLETED/FAILED outcome. */
    record AlreadyFinished(AnalysisRunStatus runStatus) implements ClaimOutcome {
    }

    /** The run was superseded by a newer evidence bundle. */
    record Stale() implements ClaimOutcome {
    }
}
