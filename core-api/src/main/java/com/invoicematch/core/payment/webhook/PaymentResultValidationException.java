package com.invoicematch.core.payment.webhook;

/** A correctly signed webhook whose body is missing required or well-formed fields. */
public class PaymentResultValidationException extends RuntimeException {

    public PaymentResultValidationException(String message) {
        super(message);
    }
}
