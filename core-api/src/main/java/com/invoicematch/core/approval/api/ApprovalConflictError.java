package com.invoicematch.core.approval.api;

import java.util.List;

/**
 * Error body for approval conflicts. {@code reasons} carries why a current but
 * unapprovable snapshot was rejected; {@code shortfalls} carries the current
 * confirmed/allocated/remaining receipt quantities of a failed allocation.
 */
public record ApprovalConflictError(
        String code, String message, List<String> reasons, List<?> shortfalls) {

    public ApprovalConflictError {
        reasons = List.copyOf(reasons);
        shortfalls = List.copyOf(shortfalls);
    }
}
