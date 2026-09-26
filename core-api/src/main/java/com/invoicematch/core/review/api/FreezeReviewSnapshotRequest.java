package com.invoicematch.core.review.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Freezes the current review subject for a case. No review snapshot id is
 * needed because the subject is by definition the latest source.
 */
public record FreezeReviewSnapshotRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion) {
}
