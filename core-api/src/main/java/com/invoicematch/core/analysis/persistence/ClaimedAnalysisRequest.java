package com.invoicematch.core.analysis.persistence;

import java.util.UUID;

/**
 * One analysis-request Outbox row a relay worker holds under a bounded lease,
 * together with the immutable canonical payload to publish. The {@code eventId}
 * is the Outbox row id and is also the message id on every (re)publication.
 */
public record ClaimedAnalysisRequest(UUID eventId, UUID analysisRunId, UUID claimToken, String payload) {
}
