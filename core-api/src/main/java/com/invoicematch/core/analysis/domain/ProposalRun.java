package com.invoicematch.core.analysis.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable input and a snapshot of separately fenced advisory execution. */
public record ProposalRun(UUID id, UUID caseId, UUID bundleId, UUID parserRunId, UUID matchResultId,
        String contextHash, String context, String status, UUID executionToken, Instant leaseUntil,
        int executionAttempt, int reservedCalls, int reservedTokens, int toolCalls, boolean leaseActive,
        boolean due, String errorCode) {
    public static final String WORKFLOW = "ai-review-v1";
    public static final int MAX_CALLS = 5;
    public static final int MAX_TOKENS = 40000;
    public static final int MAX_TOOLS = 8;
}
