package com.invoicematch.core.payment.webhook;

/** Result of ingesting one webhook: a first application or an idempotent replay. */
public enum PaymentResultIngestResult {
    APPLIED,
    REPLAY
}
