package com.invoicematch.core.analysis.domain;

import java.time.Instant;
import java.util.UUID;

/** Frozen graph input with execution-segment counters independent of thread budgets. */
public record GraphRun(UUID id, UUID caseId, long caseVersion, String context, String contextHash,
        String status, String segment, int startAttempts, int resumeAttempts, UUID token,
        Instant leaseUntil, boolean leaseActive, int checkpointCount, int writeCount, int storedBytes) {
    public static final String WORKFLOW = "ai-review-v2";
    public static final String GRAPH = "invoice-review-graph-v1";
    public static final String SERIALIZER = "graph-checkpoint-json-v1";
    public static final int SCHEMA = 4;
    public static final int MAX_BYTES = 262144;
    public static final int MAX_CHECKPOINTS = 128;
    public static final int MAX_WRITES = 512;
    public static final int MAX_TOTAL_BYTES = 8388608;
    public int attempts() { return segment.equals("START") ? startAttempts : resumeAttempts; }
    public boolean terminal() { return java.util.Set.of("COMPLETED", "FAILED", "STALE").contains(status); }
}
