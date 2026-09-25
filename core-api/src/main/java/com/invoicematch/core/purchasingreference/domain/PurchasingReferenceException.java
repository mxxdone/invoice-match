package com.invoicematch.core.purchasingreference.domain;

/**
 * Base type for the explicit application errors raised when refreshing an
 * external purchase order reference snapshot. Callers depend on this hierarchy
 * rather than on raw HTTP or JSON parsing errors.
 */
public class PurchasingReferenceException extends RuntimeException {

    public PurchasingReferenceException(String message) {
        super(message);
    }

    public PurchasingReferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
