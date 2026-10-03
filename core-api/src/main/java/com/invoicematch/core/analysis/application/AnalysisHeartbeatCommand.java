package com.invoicematch.core.analysis.application;

import java.util.UUID;

/** Machine heartbeat request for an active claim. */
public record AnalysisHeartbeatCommand(
        UUID claimToken,
        Integer inputVersion,
        String evidencePayloadHash) {
}
