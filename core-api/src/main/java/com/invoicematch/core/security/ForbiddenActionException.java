package com.invoicematch.core.security;

/**
 * Raised when an authenticated actor attempts an action their role or ownership
 * does not permit. Mapped to HTTP 403. It is thrown before any business or
 * audit side effect, so a denied action leaves no trace in the business tables.
 */
public class ForbiddenActionException extends RuntimeException {

    public ForbiddenActionException(String message) {
        super(message);
    }
}
