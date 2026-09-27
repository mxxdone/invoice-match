package com.invoicematch.core.review.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Rejects the claim against the current fresh review snapshot.
 *
 * <p>{@code decidedBy} is accepted only for backward compatibility with P1-05
 * clients and is ignored: the recorded actor is the authenticated principal.
 */
public record RejectReviewRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion,
        @NotNull UUID reviewSnapshotId,
        @NotBlank @Size(max = 128) String reviewPayloadHash,
        @NotBlank @Size(max = 1000) String reason,
        @Deprecated @Size(max = 64) String decidedBy) {
}
