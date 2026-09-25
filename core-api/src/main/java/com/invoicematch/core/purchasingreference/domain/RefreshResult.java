package com.invoicematch.core.purchasingreference.domain;

/**
 * Result of a refresh: the typed outcome plus the external version currently
 * persisted locally. For {@link RefreshOutcome#STALE_IGNORED} the version is
 * the newer stored one that was preserved.
 */
public record RefreshResult(RefreshOutcome outcome, long storedVersion) {

    public RefreshResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (storedVersion < 0) {
            throw new IllegalArgumentException("storedVersion must not be negative: " + storedVersion);
        }
    }

    public static RefreshResult of(RefreshOutcome outcome, long storedVersion) {
        return new RefreshResult(outcome, storedVersion);
    }
}
