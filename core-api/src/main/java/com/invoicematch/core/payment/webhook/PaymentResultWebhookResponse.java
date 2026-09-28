package com.invoicematch.core.payment.webhook;

/** Webhook acknowledgement: {@code APPLIED} or idempotent {@code REPLAY}. */
public record PaymentResultWebhookResponse(String status) {
}
