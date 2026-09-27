package com.invoicematch.core.review.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Confirms a case-local item mapping for one invoice line of the exact review
 * snapshot the person saw.
 *
 * <p>{@code decidedBy} is accepted only for backward compatibility with P1-05
 * clients and is ignored: the recorded actor is the authenticated principal.
 * It is never an authorization, self-approval or audit source.
 */
public record RecordMappingDecisionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion,
        @NotNull UUID reviewSnapshotId,
        @NotBlank @Size(max = 128) String reviewPayloadHash,
        @Min(1) int lineNumber,
        @NotBlank @Size(max = 64) String itemId,
        @Deprecated @Size(max = 64) String decidedBy) {
}
