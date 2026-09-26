package com.invoicematch.core.review.application;

import java.util.UUID;

/**
 * Internal test seam invoked after a review write has taken the invoice case
 * row lock and the purchase order advisory lock and before it performs the
 * final currentness validation. It lets a test hold a real review transaction
 * mid-flight and race a concurrent purchasing refresh against it.
 *
 * <p>Production uses {@link #NONE}; the type is package-private so it is not
 * part of the feature's public surface.
 */
@FunctionalInterface
interface ReviewLockInterceptor {

    ReviewLockInterceptor NONE = caseId -> { };

    void afterPurchaseOrderLocked(UUID caseId);
}
