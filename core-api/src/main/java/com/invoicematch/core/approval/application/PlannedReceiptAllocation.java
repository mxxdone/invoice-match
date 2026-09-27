package com.invoicematch.core.approval.application;

import java.time.LocalDate;

/**
 * One typed receipt line consumption taken from an independently verified match
 * result. The plan is derived only from the reconstructed typed match outcome,
 * never from client-supplied values or stored JSON.
 */
public record PlannedReceiptAllocation(
        int invoiceLineNumber,
        String purchaseOrderLineId,
        String receiptId,
        String receiptLineId,
        LocalDate receiptDate,
        long receiptLineVersion,
        int confirmedQuantity,
        int plannedQuantity) {

    public PlannedReceiptAllocation {
        if (invoiceLineNumber <= 0) {
            throw new IllegalArgumentException("invoiceLineNumber must be positive: " + invoiceLineNumber);
        }
        if (purchaseOrderLineId == null || purchaseOrderLineId.isBlank()) {
            throw new IllegalArgumentException("purchaseOrderLineId must not be blank");
        }
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("receiptId must not be blank");
        }
        if (receiptLineId == null || receiptLineId.isBlank()) {
            throw new IllegalArgumentException("receiptLineId must not be blank");
        }
        if (receiptDate == null) {
            throw new IllegalArgumentException("receiptDate must not be null");
        }
        if (receiptLineVersion < 0) {
            throw new IllegalArgumentException("receiptLineVersion must not be negative: " + receiptLineVersion);
        }
        if (confirmedQuantity < 0) {
            throw new IllegalArgumentException("confirmedQuantity must not be negative: " + confirmedQuantity);
        }
        if (plannedQuantity <= 0) {
            throw new IllegalArgumentException("plannedQuantity must be positive: " + plannedQuantity);
        }
    }
}
