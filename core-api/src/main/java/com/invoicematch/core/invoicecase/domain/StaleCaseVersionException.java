package com.invoicematch.core.invoicecase.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when a write is attempted against an invoice case using an
 * {@code expectedCaseVersion} that no longer matches the stored version. This
 * is the lost-update guard for concurrent draft edits.
 *
 * <p>The case id, the version the caller sent and the current committed version
 * are exposed so the 409 response can carry a machine-readable latest version
 * for the UI to refetch and re-render without re-deriving the conflict.
 */
public class StaleCaseVersionException extends RuntimeException {

    private final UUID caseId;
    private final long expectedVersion;
    private final long actualVersion;

    public StaleCaseVersionException(UUID caseId, long expectedVersion, long actualVersion) {
        super("Invoice case " + caseId + " expected version " + expectedVersion + " but was " + actualVersion);
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID caseId() {
        return caseId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    /** The current committed case version; the value the UI should re-read. */
    public long actualVersion() {
        return actualVersion;
    }
}
