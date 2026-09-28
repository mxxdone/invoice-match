package com.invoicematch.core.invoicecase.api;

/**
 * Stable 409 body for a stale case version. {@code latestVersion} is the
 * current committed case version the UI must re-read; {@code expectedVersion}
 * echoes the value the rejected request sent. Both are null when the conflict
 * came from an optimistic-lock failure whose expected version is unknown.
 */
public record CaseVersionConflictError(
        String code, String message, String caseId, Long expectedVersion, Long latestVersion) {
}
