package com.invoicematch.core.approval.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Exact-subject approval request. It carries no actor and no amounts: the actor
 * is derived from Authentication and the amount/allocation come from the frozen
 * review snapshot identified here.
 */
public record ApproveInvoiceCaseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion,
        @NotNull UUID reviewSnapshotId,
        @NotBlank @Size(max = 128) String reviewPayloadHash) {
}
