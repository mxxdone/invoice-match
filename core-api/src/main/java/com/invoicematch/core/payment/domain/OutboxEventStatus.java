package com.invoicematch.core.payment.domain;

/**
 * Delivery state of an {@link OutboxEvent}. The states deliberately separate
 * what a worker knows about the external HTTP call so recovery is never forced
 * to guess:
 *
 * <ul>
 *   <li>{@code READY} — safe to send, no HTTP has started;</li>
 *   <li>{@code CLAIMED} — a worker holds the lease and HTTP has definitely not
 *       started, so an expired lease can safely return the event to READY;</li>
 *   <li>{@code SENDING} — the worker committed immediately before HTTP, so the
 *       call may have started; an expired lease becomes RESULT_UNKNOWN and is
 *       never automatically resent;</li>
 *   <li>{@code DELIVERED} — a definite 2xx was observed and finalization
 *       committed;</li>
 *   <li>{@code FAILED} — a definite non-retryable outcome;</li>
 *   <li>{@code RESULT_UNKNOWN} — the outcome cannot be known (timeout, reset,
 *       ambiguous response, expired SENDING); terminal for automatic relay in
 *       Phase 1.</li>
 * </ul>
 */
public enum OutboxEventStatus {
    READY,
    CLAIMED,
    SENDING,
    DELIVERED,
    FAILED,
    RESULT_UNKNOWN;

    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED || this == RESULT_UNKNOWN;
    }
}
