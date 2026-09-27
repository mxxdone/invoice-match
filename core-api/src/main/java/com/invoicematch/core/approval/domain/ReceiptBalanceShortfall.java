package com.invoicematch.core.approval.domain;

/**
 * Current receipt line balance at the moment approval tried to consume it. The
 * values let the caller explain exactly why the loser of a concurrent approval
 * could not allocate. Aggregate quantities are {@code long} so a total across
 * many lines cannot overflow an {@code int}.
 */
public record ReceiptBalanceShortfall(
        String receiptId,
        String receiptLineId,
        int confirmedQuantity,
        long allocatedQuantity,
        long remainingQuantity,
        long requestedQuantity) {
}
