package com.invoicematch.core.payment.adapter;

import java.time.Duration;

/**
 * Explicit HTTP outcome of one export attempt. The relay maps each variant to a
 * state transition; uncertainty is never collapsed into "failed" so a payment
 * is never blindly resent.
 */
public sealed interface PaymentExportOutcome {

    /** A definite 2xx: the ERP accepted the request. */
    record Acknowledged(int httpStatus) implements PaymentExportOutcome {
    }

    /** A definite non-retryable 4xx (bad payload, conflict, forbidden...). */
    record NonRetryable(int httpStatus, String errorCode, String detail) implements PaymentExportOutcome {
    }

    /** An explicit 429: safe to retry the same key/payload after a backoff. */
    record RateLimited(int httpStatus, String errorCode, String detail, Duration retryAfter)
            implements PaymentExportOutcome {
    }

    /**
     * The outcome cannot be known (timeout, reset, ambiguous/5xx response,
     * expired SENDING). {@code httpStatus} is null for transport/lease failures
     * and the 5xx status when the server did answer. Terminal for automatic
     * relay in Phase 1.
     */
    record ResultUnknown(Integer httpStatus, String errorCode, String detail) implements PaymentExportOutcome {
    }
}
