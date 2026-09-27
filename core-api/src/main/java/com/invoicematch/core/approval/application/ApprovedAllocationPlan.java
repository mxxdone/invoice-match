package com.invoicematch.core.approval.application;

import com.invoicematch.core.shared.domain.Money;
import java.util.List;

/**
 * The allocation intent frozen into the approved review snapshot: the exact
 * FIFO receipt line consumptions of every invoice line plus the approved total
 * amount. It is derived from the canonical snapshot payload only.
 */
public record ApprovedAllocationPlan(List<PlannedReceiptAllocation> allocations, Money totalAmount) {

    public ApprovedAllocationPlan {
        allocations = List.copyOf(allocations);
        if (totalAmount == null) {
            throw new IllegalArgumentException("totalAmount must not be null");
        }
    }
}
