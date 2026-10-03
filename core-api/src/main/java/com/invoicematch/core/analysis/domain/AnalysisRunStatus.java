package com.invoicematch.core.analysis.domain;

/**
 * Lifecycle of one parser-backed analysis run.
 *
 * <p>{@code QUEUED} is a fresh reservation; {@code RUNNING} is held by one
 * machine worker under a bounded lease; {@code COMPLETED} and {@code FAILED} are
 * terminal outcomes decided by the last document result; recovery checkpoints
 * use RETRY_SCHEDULED and DEAD_LETTERED without rewriting these outcomes; {@code STALE} is the
 * preserved record of a run superseded by a newer evidence bundle. A stale run's
 * results are never used as approval evidence but are never deleted either.
 */
public enum AnalysisRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    STALE
}
