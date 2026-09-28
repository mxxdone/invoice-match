package com.invoicematch.core.payment.application;

import com.invoicematch.core.payment.adapter.PaymentExportOutcome;
import java.util.UUID;

/**
 * Internal test seam for the relay. The hooks sit exactly on the crash windows
 * the contract names, after the relevant short transaction has committed, so a
 * test can stop a worker and prove recovery. Production uses {@link #NONE}; the
 * type is package-private so it is not part of the feature's public surface.
 */
interface PaymentExportInterceptor {

    PaymentExportInterceptor NONE = new PaymentExportInterceptor() {
    };

    /** The event is CLAIMED and committed; HTTP has not started. */
    default void afterClaimed(UUID eventId) {
    }

    /** The event is SENDING and committed; HTTP is about to start. */
    default void afterSendingCommitted(UUID eventId) {
    }

    /** HTTP has returned an outcome but finalization has not committed. */
    default void beforeFinalize(UUID eventId, PaymentExportOutcome outcome) {
    }

    /** Finalization committed. */
    default void afterFinalized(UUID eventId) {
    }
}
