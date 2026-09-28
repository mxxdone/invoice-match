package com.invoicematch.core.payment.domain;

public enum DeliveryAttemptOutcome {
    SENDING,
    ACKNOWLEDGED,
    FAILED,
    RETRY_SCHEDULED,
    RESULT_UNKNOWN,
    LEASE_EXPIRED
}
