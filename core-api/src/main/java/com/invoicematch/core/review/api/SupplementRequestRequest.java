package com.invoicematch.core.review.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Confirms a supplement request against the current fresh review snapshot.
 */
public record SupplementRequestRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion,
        @NotNull UUID reviewSnapshotId,
        @NotBlank @Size(max = 128) String reviewPayloadHash,
        @NotBlank @Size(max = 1000) String reason,
        @Size(max = 64) String decidedBy) {
}
