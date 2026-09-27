package com.invoicematch.core.approval.domain;

import java.util.List;
import java.util.UUID;

/**
 * A concurrent approval already consumed part of a shared receipt line, so the
 * frozen expected plan can no longer be allocated in full. Approval is
 * all-or-nothing: no decision, allocation, payment request or case transition is
 * written. The shortfalls report the current confirmed, allocated and remaining
 * quantities per referenced receipt line.
 */
public class InsufficientReceiptBalanceException extends RuntimeException {

    private final UUID caseId;
    private final UUID reviewSnapshotId;
    private final List<ReceiptBalanceShortfall> shortfalls;

    public InsufficientReceiptBalanceException(
            UUID caseId, UUID reviewSnapshotId, List<ReceiptBalanceShortfall> shortfalls) {
        super("Review snapshot " + reviewSnapshotId + " of case " + caseId
                + " cannot be allocated; insufficient receipt balance: " + shortfalls);
        this.caseId = caseId;
        this.reviewSnapshotId = reviewSnapshotId;
        this.shortfalls = List.copyOf(shortfalls);
    }

    public UUID caseId() {
        return caseId;
    }

    public UUID reviewSnapshotId() {
        return reviewSnapshotId;
    }

    public List<ReceiptBalanceShortfall> shortfalls() {
        return shortfalls;
    }
}
