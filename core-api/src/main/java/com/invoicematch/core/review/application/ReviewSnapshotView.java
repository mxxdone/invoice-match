package com.invoicematch.core.review.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of one frozen review snapshot. {@code payload} is the canonical
 * JSON and {@code payloadHash} is its SHA-256; both are returned unchanged from
 * storage. {@code snapshotNumber} is the per-case monotonic business order.
 */
public record ReviewSnapshotView(
        UUID id,
        UUID invoiceCaseId,
        int snapshotNumber,
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        UUID matchResultId,
        Integer matchResultNumber,
        long targetCaseVersion,
        long purchasingSnapshotVersion,
        String purchasingSnapshotHash,
        int mappingWatermark,
        String payloadHash,
        JsonNode payload,
        Instant createdAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ReviewSnapshotView from(ReviewSnapshot snapshot) {
        return new ReviewSnapshotView(
                snapshot.id(),
                snapshot.invoiceCaseId(),
                snapshot.snapshotNumber(),
                snapshot.evidenceBundleId(),
                snapshot.targetEvidenceBundleVersion(),
                snapshot.matchResultId(),
                snapshot.matchResultNumber(),
                snapshot.targetCaseVersion(),
                snapshot.purchasingSnapshotVersion(),
                snapshot.purchasingSnapshotHash(),
                snapshot.mappingWatermark(),
                snapshot.payloadHash(),
                parse(snapshot.payload()),
                snapshot.createdAt());
    }

    private static JsonNode parse(String payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored review snapshot payload is not readable", e);
        }
    }
}
