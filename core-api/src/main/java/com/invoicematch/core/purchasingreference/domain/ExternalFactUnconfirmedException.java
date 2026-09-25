package com.invoicematch.core.purchasingreference.domain;

/**
 * The external aggregate contains a purchase order or receipt whose fact is not
 * yet confirmed. Unconfirmed facts must not be pulled into the local reference
 * snapshot.
 */
public class ExternalFactUnconfirmedException extends PurchasingReferenceException {

    public ExternalFactUnconfirmedException(String message) {
        super(message);
    }
}
