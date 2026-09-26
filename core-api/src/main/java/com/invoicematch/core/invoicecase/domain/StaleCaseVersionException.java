package com.invoicematch.core.invoicecase.domain;

import java.util.UUID;

/**
 * Thrown when a write is attempted against an invoice case using an
 * {@code expectedCaseVersion} that no longer matches the stored version. This
 * is the lost-update guard for concurrent draft edits.
 */
public class StaleCaseVersionException extends RuntimeException {

    public StaleCaseVersionException(UUID caseId, long expectedVersion, long actualVersion) {
        super("Invoice case " + caseId + " expected version " + expectedVersion + " but was " + actualVersion);
    }
}
