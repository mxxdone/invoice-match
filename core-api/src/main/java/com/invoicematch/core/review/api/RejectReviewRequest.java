package com.invoicematch.core.review.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Rejects the claim against the current fresh review snapshot.
 *
 * <p><strong>Non-authoritative until P1-06:</strong> {@code decidedBy} is a
 * client-supplied, unauthenticated placeholder recorded for traceability only;
 * it is not an authorization or audit source. P1-06 replaces it with the
 * authenticated principal.
 */
public record RejectReviewRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion,
        @NotNull UUID reviewSnapshotId,
        @NotBlank @Size(max = 128) String reviewPayloadHash,
        @NotBlank @Size(max = 1000) String reason,
        @Size(max = 64) String decidedBy) {
}
