package com.invoicematch.core.payment.webhook;

/**
 * The webhook is authentic but conflicts with recorded state: a repeated event
 * id with a different payload, a payment key mismatch, or an illegal/out-of-order
 * result transition. A conflict never changes any existing record.
 */
public class PaymentResultConflictException extends RuntimeException {

    public PaymentResultConflictException(String message) {
        super(message);
    }
}
