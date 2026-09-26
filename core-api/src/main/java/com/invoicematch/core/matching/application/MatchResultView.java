package com.invoicematch.core.matching.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.matching.domain.MatchResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of one persisted match result. {@code payload} is the canonical
 * JSON and {@code resultHash} is its SHA-256; both are returned unchanged from
 * storage so a re-run with the same semantic inputs reproduces the same hash.
 * {@code resultNumber} is the per-case monotonic append order.
 */
public record MatchResultView(
        UUID id,
        UUID invoiceCaseId,
        UUID evidenceBundleId,
        int resultNumber,
        String resultHash,
        JsonNode payload,
        Instant createdAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static MatchResultView from(MatchResult result) {
        return new MatchResultView(
                result.id(),
                result.invoiceCaseId(),
                result.evidenceBundleId(),
                result.resultNumber(),
                result.resultHash(),
                parse(result.payload()),
                result.createdAt());
    }

    private static JsonNode parse(String payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored match result payload is not readable", e);
        }
    }
}
