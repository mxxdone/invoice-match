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
 * <p><strong>Non-authoritative until P1-06:</strong> {@code decidedBy} is a
 * client-supplied, unauthenticated placeholder. It is recorded for traceability
 * during Phase 1 only and must not be trusted for authorization, self-approval
 * or audit. P1-06 replaces it with the authenticated principal and removes the
 * client-supplied actor from the contract.
 */
public record RecordMappingDecisionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion,
        @NotNull UUID reviewSnapshotId,
        @NotBlank @Size(max = 128) String reviewPayloadHash,
        @Min(1) int lineNumber,
        @NotBlank @Size(max = 64) String itemId,
        @Size(max = 64) String decidedBy) {
}
