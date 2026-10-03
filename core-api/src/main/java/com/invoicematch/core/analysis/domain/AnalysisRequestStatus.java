package com.invoicematch.core.analysis.domain;

/**
 * Lifecycle of an analysis-request Outbox row. {@code READY} is a fresh
 * reservation due for publication; {@code CLAIMED} is held by one relay worker
 * under a bounded DB-time lease; {@code PUBLISHED} is terminal once publisher
 * confirm ACKed with no mandatory return; {@code CANCELLED} is terminal for a
 * reservation superseded by a newer evidence bundle. PUBLISHED/CANCELLED never
 * leave their terminal state, and a stale claim token can never rewrite a
 * terminal row.
 */
public enum AnalysisRequestStatus {
    READY,
    CLAIMED,
    PUBLISHED,
    CANCELLED
}
