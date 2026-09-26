package com.invoicematch.core.matching.domain;

import java.util.List;
import java.util.Objects;

/**
 * The deterministic outcome computed for one invoice line: the selected
 * purchase order line (if any), the compared values, the expected non-consuming
 * allocation plan and this line's exceptions.
 *
 * <p>{@code availableConfirmedQuantity} is the aggregate remaining confirmed
 * quantity for the matched purchase order line immediately before this line's
 * plan, so multiple invoice lines sharing one purchase order line cannot
 * overbook it. Aggregate quantities are {@code long} so summing
 * {@link Integer#MAX_VALUE} receipt lines can never wrap.
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
        long availableConfirmedQuantity,
        List<PlannedAllocation> expectedAllocationPlan,
        long plannedQuantity,
        List<MatchException> exceptions) {

    public MatchLineOutcome {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive: " + lineNumber);
        }
        Objects.requireNonNull(status, "status");
        if (availableConfirmedQuantity < 0) {
            throw new IllegalArgumentException(
                    "availableConfirmedQuantity must not be negative: " + availableConfirmedQuantity);
        }
        if (plannedQuantity < 0) {
            throw new IllegalArgumentException("plannedQuantity must not be negative: " + plannedQuantity);
        }
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
