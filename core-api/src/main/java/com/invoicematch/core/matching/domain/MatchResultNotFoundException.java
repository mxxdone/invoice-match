package com.invoicematch.core.matching.domain;

import java.util.UUID;

/**
 * No persisted match result exists for the requested invoice case.
 */
public class MatchResultNotFoundException extends RuntimeException {

    public MatchResultNotFoundException(UUID caseId) {
        super("No match result exists for invoice case " + caseId);
    }
}
