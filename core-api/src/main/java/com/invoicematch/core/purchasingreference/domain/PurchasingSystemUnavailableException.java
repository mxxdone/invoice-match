package com.invoicematch.core.purchasingreference.domain;

/**
 * The external purchasing system could not be reached or returned an
 * unexpected HTTP status (timeout, connection failure, 5xx). This is an
 * infrastructure failure, not a statement about the purchase order facts.
 */
public class PurchasingSystemUnavailableException extends PurchasingReferenceException {

    public PurchasingSystemUnavailableException(String message) {
        super(message);
    }

    public PurchasingSystemUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
