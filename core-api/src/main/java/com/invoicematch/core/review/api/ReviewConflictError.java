package com.invoicematch.core.review.api;

import java.util.List;

/**
 * Error body for review conflicts. {@code reasons} carries the explicit stale
 * reasons of a {@code STALE_REVIEW_TARGET} response and is empty otherwise.
 */
public record ReviewConflictError(String code, String message, List<String> reasons) {

    public ReviewConflictError {
        reasons = List.copyOf(reasons);
    }
}
