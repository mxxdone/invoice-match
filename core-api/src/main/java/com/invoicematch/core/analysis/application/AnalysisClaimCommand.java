package com.invoicematch.core.analysis.application;

import java.util.UUID;

/**
 * Machine claim request. The consumer never supplies documents, URLs or object
 * keys; only the frozen request identity it received from the broker, which the
 * application compares against the authoritative run and Outbox event.
 */
public record AnalysisClaimCommand(
        UUID eventId,
        Integer inputVersion,
        String evidencePayloadHash,
        String workflowVersion) {
}
