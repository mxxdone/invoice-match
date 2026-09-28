package com.invoicematch.core.payment.adapter;

/**
 * External ERP payment-export port. The Phase 1 implementation is a direct HTTP
 * adapter; P1-09 replaces the receiving side with an idempotent receiver and
 * webhook. The relay never sees raw transport errors.
 */
public interface PaymentExportClient {

    PaymentExportOutcome send(PaymentExportRequest request);
}
