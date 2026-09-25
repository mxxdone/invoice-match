package com.invoicematch.core.purchasingreference.domain;

/**
 * The fetched aggregate violates the expected identity: the purchase order id,
 * supplier or a child reference does not match what the caller asked for.
 */
public class ExternalReferenceMismatchException extends PurchasingReferenceException {

    public ExternalReferenceMismatchException(String message) {
        super(message);
    }
}
