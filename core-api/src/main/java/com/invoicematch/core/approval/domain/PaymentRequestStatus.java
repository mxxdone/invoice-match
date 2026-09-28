package com.invoicematch.core.approval.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Export lifecycle of a {@link PaymentRequest}. P1-07 creates exactly one
 * {@code NOT_SENT} record per approval; P1-08 owns the ERP hand-off state
 * machine (Spec 10.3). The transitions are duplicated by a database trigger so
 * raw SQL cannot move a payment outside this contract.
 *
 * <pre>
 * NOT_SENT → SENDING
 *   ├─ ACKNOWLEDGED
 *   ├─ RETRY_SCHEDULED → SENDING
 *   ├─ FAILED
 *   └─ RESULT_UNKNOWN
 * </pre>
 */
public enum PaymentRequestStatus {
    NOT_SENT,
    SENDING,
    ACKNOWLEDGED,
    RETRY_SCHEDULED,
    FAILED,
    RESULT_UNKNOWN;

    private static final Map<PaymentRequestStatus, Set<PaymentRequestStatus>> ALLOWED_TRANSITIONS = Map.of(
            NOT_SENT, EnumSet.of(SENDING),
            SENDING, EnumSet.of(ACKNOWLEDGED, RETRY_SCHEDULED, FAILED, RESULT_UNKNOWN),
            RETRY_SCHEDULED, EnumSet.of(SENDING),
            ACKNOWLEDGED, EnumSet.noneOf(PaymentRequestStatus.class),
            FAILED, EnumSet.noneOf(PaymentRequestStatus.class),
            RESULT_UNKNOWN, EnumSet.noneOf(PaymentRequestStatus.class));

    public boolean canTransitionTo(PaymentRequestStatus target) {
        return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public Set<PaymentRequestStatus> allowedTransitions() {
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.get(this));
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
