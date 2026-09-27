package com.invoicematch.core.approval.application;

import java.util.UUID;

/**
 * Internal test seam for the approval transaction. It exposes the points a
 * deterministic concurrency or rollback test must observe: after the external
 * purchasing fetch (before the approval transaction opens), after the purchase
 * order advisory lock, and after each written stage so a failure can be injected
 * to prove the whole allocation set rolls back.
 *
 * <p>Production uses {@link #NONE}; the type is package-private so it is not part
 * of the feature's public surface.
 */
interface ApprovalInterceptor {

    ApprovalInterceptor NONE = new ApprovalInterceptor() {
    };

    default void afterExternalFetch(UUID caseId) {
    }

    default void afterPurchaseOrderLocked(UUID caseId) {
    }

    default void afterDecisionWritten(UUID caseId) {
    }

    default void afterAllocationsWritten(UUID caseId) {
    }

    default void afterPaymentRequestWritten(UUID caseId) {
    }
}
