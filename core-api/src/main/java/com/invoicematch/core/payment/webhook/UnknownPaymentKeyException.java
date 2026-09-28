package com.invoicematch.core.payment.webhook;

/** The webhook references an export key that does not belong to any outbox event. */
public class UnknownPaymentKeyException extends RuntimeException {

    private final String externalPaymentKey;

    public UnknownPaymentKeyException(String externalPaymentKey) {
        super("no payment export exists for the supplied idempotency key");
        this.externalPaymentKey = externalPaymentKey;
    }

    public String externalPaymentKey() {
        return externalPaymentKey;
    }
}
