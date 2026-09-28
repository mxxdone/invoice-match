package com.invoicematch.core.payment.webhook;

/** A webhook whose signature is missing, malformed, expired or not authentic. */
public class WebhookSignatureException extends RuntimeException {

    public WebhookSignatureException(String message) {
        super(message);
    }
}
