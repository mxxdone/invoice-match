package com.invoicematch.core.payment.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Wire body of the mock-ERP result webhook. Unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
record PaymentResultWebhookRequest(
        String provider,
        String externalEventId,
        String externalPaymentKey,
        String paymentRequestId,
        String outcome,
        String externalReference) {
}
