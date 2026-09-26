package com.invoicematch.core.matching.domain;

import java.util.List;
import java.util.Objects;

/**
 * The deterministic outcome computed for one invoice line: the selected
 * purchase order line (if any), the compared values, the expected non-consuming
 * allocation plan and this line's exceptions.
 */
public record MatchLineOutcome(
        int lineNumber,
        String rawItemName,
        String confirmedItemId,
        MatchLineStatus status,
        List<String> candidatePoLineIds,
        MatchPoLine purchaseOrderLine,
        int invoiceQuantity,
        long invoiceUnitPrice,
        int availableConfirmedQuantity,
        List<PlannedAllocation> expectedAllocationPlan,
        int plannedQuantity,
        List<MatchException> exceptions) {

    public MatchLineOutcome {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive: " + lineNumber);
        }
        Objects.requireNonNull(status, "status");
        candidatePoLineIds = List.copyOf(Objects.requireNonNull(candidatePoLineIds, "candidatePoLineIds"));
        expectedAllocationPlan =
                List.copyOf(Objects.requireNonNull(expectedAllocationPlan, "expectedAllocationPlan"));
        exceptions = List.copyOf(Objects.requireNonNull(exceptions, "exceptions"));
    }

    /**
     * A line is complete when its expected plan covers the full invoice quantity.
     */
    public boolean hasCompleteExpectedPlan() {
        return plannedQuantity == invoiceQuantity;
    }
}
