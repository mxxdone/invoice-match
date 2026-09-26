package com.invoicematch.core.matching.domain;

import java.util.UUID;

/**
 * The case is not in a state where matching is allowed, or the purchasing
 * reference snapshot it needs is not currently available.
 */
public class MatchStateConflictException extends RuntimeException {

    public MatchStateConflictException(UUID caseId, String message) {
        super("Invoice case " + caseId + ": " + message);
    }
}
