package com.invoicematch.core.purchasingreference.domain;

/**
 * The external aggregate carries an invalid value (for example a non-positive
 * ordered quantity, a negative unit price or a missing required field). The
 * refresh is rejected without persisting anything.
 */
public class InvalidExternalFactException extends PurchasingReferenceException {

    public InvalidExternalFactException(String message) {
        super(message);
    }

    public InvalidExternalFactException(String message, Throwable cause) {
        super(message, cause);
    }
}
