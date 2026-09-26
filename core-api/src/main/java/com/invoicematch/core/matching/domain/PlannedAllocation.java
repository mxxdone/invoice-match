package com.invoicematch.core.matching.domain;

import java.time.LocalDate;

/**
 * One line of the expected, non-consuming allocation plan: how much of an
 * invoice line would be planned against a confirmed receipt line, in FIFO
 * order.
 *
 * <p>This is a plan only. P1-04 does not create a {@code ReceiptAllocation} or
 * consume any receipt balance; the approval ticket revalidates and consumes the
 * plan against the then-current balance.
 */
public record PlannedAllocation(
        String receiptId,
        String receiptLineId,
        LocalDate receiptDate,
        long receiptLineVersion,
        int confirmedQuantity,
        int plannedQuantity) {

    public PlannedAllocation {
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
        if (plannedQuantity > confirmedQuantity) {
            throw new IllegalArgumentException(
                    "plannedQuantity " + plannedQuantity + " exceeds confirmedQuantity " + confirmedQuantity);
        }
    }
}
