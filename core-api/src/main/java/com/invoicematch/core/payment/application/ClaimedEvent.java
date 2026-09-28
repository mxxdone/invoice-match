package com.invoicematch.core.payment.application;

import java.util.UUID;

/** An event a worker holds under a lease; HTTP has definitely not started. */
public record ClaimedEvent(UUID eventId, UUID claimToken) {
}
