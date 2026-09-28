package com.invoicematch.core.payment.webhook;

import java.util.UUID;

/**
 * A signature-verified external payment result. {@code payloadHash} is the
 * SHA-256 of the exact received body, so the same event id with any different
 * bytes is a conflict while a byte-identical retry is a replay.
 */
public record PaymentResultCommand(
        String provider,
        String externalEventId,
        String externalPaymentKey,
        UUID paymentRequestId,
        PaymentResultOutcome outcome,
        String payloadHash,
        String externalReference) {
}
