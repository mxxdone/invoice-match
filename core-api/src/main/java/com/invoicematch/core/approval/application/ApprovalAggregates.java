package com.invoicematch.core.approval.application;

import com.invoicematch.core.approval.domain.ReceiptAllocation;
import java.util.List;

/**
 * Checked aggregate arithmetic for approval. Totals are {@code long} and use
 * {@link Math#addExact} so a sum of quantities can never silently wrap an
 * {@code int}.
 */
public final class ApprovalAggregates {

    private ApprovalAggregates() {
    }

    public static long sumPlannedQuantity(List<PlannedReceiptAllocation> allocations) {
        long total = 0L;
        for (PlannedReceiptAllocation allocation : allocations) {
            total = Math.addExact(total, allocation.plannedQuantity());
        }
        return total;
    }

    public static long sumAllocatedQuantity(List<ReceiptAllocation> allocations) {
        long total = 0L;
        for (ReceiptAllocation allocation : allocations) {
            total = Math.addExact(total, allocation.allocatedQuantity());
        }
        return total;
    }
}
