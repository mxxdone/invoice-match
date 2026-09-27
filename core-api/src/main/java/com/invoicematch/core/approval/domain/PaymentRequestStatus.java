package com.invoicematch.core.approval.domain;

/**
 * Export lifecycle of a {@link PaymentRequest}. P1-07 creates exactly one
 * {@code PENDING} record per approval; P1-08 adds the Outbox relay and expands
 * the export state machine.
 */
public enum PaymentRequestStatus {
    PENDING
}
