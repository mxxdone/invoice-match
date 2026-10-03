package com.invoicematch.core.analysis.domain;

/**
 * The only analysis-request Outbox states implemented in P2-05: {@code READY}
 * for a fresh reservation and {@code CANCELLED} for a reservation superseded by
 * a newer evidence bundle. Sending/lease states are P2-06.
 */
public enum AnalysisRequestStatus {
    READY,
    CANCELLED
}
