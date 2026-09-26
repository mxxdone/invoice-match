package com.invoicematch.core.review.domain;

import java.util.UUID;

/**
 * The case is not in a state that permits the requested review operation, or a
 * required source (frozen bundle, match result, purchasing snapshot) is missing
 * or does not match. Reported as an HTTP 409 with no side effects.
 */
public class ReviewStateConflictException extends RuntimeException {

    public ReviewStateConflictException(UUID caseId, String message) {
        super("Invoice case " + caseId + ": " + message);
    }
}
