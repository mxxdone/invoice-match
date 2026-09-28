package com.invoicematch.core.payment.application;

import java.util.UUID;

/**
 * Raised when a claim-token compare-and-set finds the row is no longer in the
 * expected state (expired lease, recovered event, or a newer worker). It rolls
 * the current short transaction back so a stale worker can never overwrite the
 * current state.
 */
public class StaleClaimException extends RuntimeException {

    public StaleClaimException(UUID eventId, String reason) {
        super("stale claim for outbox event " + eventId + ": " + reason);
    }
}
