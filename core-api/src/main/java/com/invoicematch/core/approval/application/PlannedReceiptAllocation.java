package com.invoicematch.core.approval.application;

import java.time.LocalDate;

/**
 * One receipt line consumption taken from the frozen review snapshot's
 * expected allocation plan. The plan is derived only from the canonical
 * approved snapshot, never from client-supplied amounts.
 */
public record PlannedReceiptAllocation(
        int invoiceLineNumber,
        String receiptId,
        String receiptLineId,
        LocalDate receiptDate,
        int plannedQuantity) {

    public PlannedReceiptAllocation {
        if (invoiceLineNumber <= 0) {
            throw new IllegalArgumentException("invoiceLineNumber must be positive: " + invoiceLineNumber);
        }
        if (plannedQuantity <= 0) {
            throw new IllegalArgumentException("plannedQuantity must be positive: " + plannedQuantity);
        }
    }
}
