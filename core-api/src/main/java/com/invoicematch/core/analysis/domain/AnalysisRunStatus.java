package com.invoicematch.core.analysis.domain;

/**
 * The only AnalysisRun states implemented in P2-05: a freshly reserved
 * {@code QUEUED} run, and a run {@code STALE}d because a newer evidence bundle
 * for the same case was submitted. Future execution states are P2-06.
 */
public enum AnalysisRunStatus {
    QUEUED,
    STALE
}
