package com.invoicematch.core.review.api;

import java.util.List;

/**
 * Error body for review conflicts. {@code reasons} carries the explicit stale
 * reasons of a {@code STALE_REVIEW_TARGET} response and is empty otherwise.
 * {@code currentCaseVersion} and {@code currentCaseStatus} are the committed
 * case identifiers captured when a stale target was rejected, so the UI can
 * refetch the latest subject; they are null for every other conflict.
 */
public record ReviewConflictError(
        String code,
        String message,
        List<String> reasons,
        Long currentCaseVersion,
        String currentCaseStatus) {

    public ReviewConflictError {
        reasons = List.copyOf(reasons);
    }
}
