package com.invoicematch.core.matching.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for running a deterministic match. The request id provides
 * technical idempotency for this write; it is unrelated to the business
 * duplicate invoice exception.
 */
public record RunMatchRequest(@NotBlank @Size(max = 128) String requestId) {
}
