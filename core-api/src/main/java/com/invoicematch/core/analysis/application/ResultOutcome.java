package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.domain.AnalysisRunStatus;

/**
 * The disposition of a per-document result submission.
 */
public sealed interface ResultOutcome {

    /** A new result was stored; {@code runStatus} is RUNNING until the last
     * manifest document is stored, then COMPLETED or FAILED. */
    record Accepted(AnalysisRunStatus runStatus) implements ResultOutcome {
    }

    /** The same canonical hash was already stored for this run/document. */
    record Replayed(AnalysisRunStatus runStatus) implements ResultOutcome {
    }

    /** The run was superseded; the result is ignored and no row is added. */
    record Stale() implements ResultOutcome {
    }
}
