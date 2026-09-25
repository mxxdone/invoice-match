package com.invoicematch.core.shared.domain;

/**
 * Thrown when a domain value violates a business invariant (for example a
 * negative KRW amount or a non-positive quantity). This is an input validation
 * failure, not a business exception such as a matching mismatch.
 */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
