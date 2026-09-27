package com.invoicematch.core.approval.application;

import java.util.List;

/**
 * One allocated receipt line as reported back to the caller. It mirrors the
 * frozen plan exactly.
 */
public record ApprovedAllocation(
        int invoiceLineNumber, String receiptId, String receiptLineId, int allocatedQuantity) {
}
