package com.invoicematch.core.invoicecase.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an {@link InvoiceCase}.
 *
 * <p>This enum defines the full confirmed non-AI Phase 1 state contract,
 * including transitions whose commands are implemented by later tickets. It
 * deliberately excludes Phase 2 analysis behaviour such as {@code ANALYZING},
 * and approval allocation guarded transitions are enforced by their command
 * services, not by this structural state machine.
 *
 * <pre>
 * DRAFT
 *   → SUBMITTED
 *   → REVIEW_PENDING
 *       → SUPPLEMENT_REQUIRED → SUBMITTED
 *       → REJECTED
 *       → EXPORT_PENDING → EXPORTED
 * </pre>
 */
public enum InvoiceCaseStatus {
    DRAFT,
    SUBMITTED,
    REVIEW_PENDING,
    SUPPLEMENT_REQUIRED,
    REJECTED,
    EXPORT_PENDING,
    EXPORTED;

    private static final Map<InvoiceCaseStatus, Set<InvoiceCaseStatus>> ALLOWED_TRANSITIONS = Map.of(
            DRAFT, EnumSet.of(SUBMITTED),
            SUBMITTED, EnumSet.of(REVIEW_PENDING),
            REVIEW_PENDING, EnumSet.of(SUPPLEMENT_REQUIRED, REJECTED, EXPORT_PENDING),
            SUPPLEMENT_REQUIRED, EnumSet.of(SUBMITTED),
            EXPORT_PENDING, EnumSet.of(EXPORTED),
            REJECTED, EnumSet.noneOf(InvoiceCaseStatus.class),
            EXPORTED, EnumSet.noneOf(InvoiceCaseStatus.class));

    public boolean canTransitionTo(InvoiceCaseStatus target) {
        return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public Set<InvoiceCaseStatus> allowedTransitions() {
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.get(this));
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
