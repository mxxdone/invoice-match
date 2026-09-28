package com.invoicematch.core.payment.adapter;

import java.util.UUID;

/**
 * The transport-neutral export call. The idempotency key is stable for the
 * lifetime of the logical PaymentRequest so an explicit safe retry (429) reuses
 * the same business identity.
 */
public record PaymentExportRequest(UUID paymentRequestId, String idempotencyKey, String payload) {
}
