package com.invoicematch.core.invoicecase.application;

/**
 * Thrown when a request id is reused with a canonical payload that differs from
 * the payload that was originally processed. The request is rejected rather
 * than silently replayed as success.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String scope, String resourceKey, String requestId) {
        super("Request id " + requestId + " was already used for " + scope + "/" + resourceKey
                + " with a different payload");
    }
}
