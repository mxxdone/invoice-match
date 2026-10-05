package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Human-facing graph projections; never exposes checkpoint bodies or lease tokens. */
public final class GraphViews {
    private GraphViews() {}
    public record Summary(UUID id, String status, String segment, boolean current, boolean supported,
            long caseVersion, String contextHash, String payloadHash, int startAttempts, int resumeAttempts,
            int reservedCalls, int reservedTokens, int toolCalls, String errorCode,
            List<String> completedStages, UUID predecessorId, Instant createdAt) {}
    public record Pending(String interruptId, String checkpointHash, int reviewVersion,
            UUID documentStageRef, UUID mappingStageRef, List<String> reasonCodes,
            JsonNode document, JsonNode mapping) {}
    public record Review(UUID id, String actor, String reason, JsonNode confirmation, Instant createdAt,
            String resumeStatus) {}
    public record View(Summary run, Pending pending, Review review, JsonNode payload,
            List<ProposalSourceCatalog.Segment> sources) {}
    public record Page(boolean enabled, View latest, List<Summary> history) {}
}
