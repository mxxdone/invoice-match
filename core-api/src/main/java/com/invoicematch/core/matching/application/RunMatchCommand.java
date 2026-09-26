package com.invoicematch.core.matching.application;

import java.util.UUID;

/**
 * Command to run a deterministic match for the latest frozen evidence bundle of
 * one invoice case. The request id provides technical idempotency and is
 * unrelated to the business duplicate invoice exception.
 */
public record RunMatchCommand(UUID caseId, String requestId) {
}
