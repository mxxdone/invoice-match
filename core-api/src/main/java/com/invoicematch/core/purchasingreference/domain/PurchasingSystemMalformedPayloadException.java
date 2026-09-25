package com.invoicematch.core.purchasingreference.domain;

/**
 * The external purchasing system returned a body that does not match the
 * agreed JSON contract (unparseable JSON or a structurally wrong document).
 */
public class PurchasingSystemMalformedPayloadException extends PurchasingReferenceException {

    public PurchasingSystemMalformedPayloadException(String message) {
        super(message);
    }

    public PurchasingSystemMalformedPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
