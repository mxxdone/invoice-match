package com.invoicematch.core.payment.application;

import java.util.UUID;

/** An event committed to SENDING; the HTTP call may now start. */
public record SendingEvent(
        UUID eventId,
        UUID claimToken,
        UUID paymentRequestId,
        UUID invoiceCaseId,
        String idempotencyKey,
        String payload,
        int attemptNumber) {
}
