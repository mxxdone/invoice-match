package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A single {@code analysis_run} row read under the {@code invoice_case -> run}
 * lock order. {@code leaseActive} is computed by PostgreSQL {@code now}
 * semantics on the same statement that reads the row, so the reclaim decision is
 * never based on an application clock.
 */
public record AnalysisRunSnapshot(
        UUID runId,
        UUID invoiceCaseId,
        UUID evidenceBundleId,
        int inputVersion,
        String evidencePayloadHash,
        String workflowVersion,
        AnalysisRunStatus status,
        UUID executionToken,
        Instant leaseUntil,
        int executionAttempt,
        boolean leaseActive,
        int attemptLimit) {
}
