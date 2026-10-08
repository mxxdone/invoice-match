package com.invoicematch.core.approval.application;

import com.invoicematch.core.approval.domain.ApprovalNotPermittedException;
import com.invoicematch.core.approval.domain.InsufficientReceiptBalanceException;
import com.invoicematch.core.approval.domain.ReceiptBalanceShortfall;
import com.invoicematch.core.approval.persistence.ReceiptAllocationRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshotRepository;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Receipt fact and balance validation; the caller owns the case and purchasing locks. */
@Component
class ApprovalReceiptAllocator {
    private final ReceiptLineSnapshotRepository receiptLines;
    private final ReceiptSnapshotRepository receiptSnapshots;
    private final ReceiptAllocationRepository receiptAllocations;

    ApprovalReceiptAllocator(ReceiptLineSnapshotRepository receiptLines,
            ReceiptSnapshotRepository receiptSnapshots, ReceiptAllocationRepository receiptAllocations) {
        this.receiptLines = receiptLines;
        this.receiptSnapshots = receiptSnapshots;
        this.receiptAllocations = receiptAllocations;
    }

    /**
     * Validates the frozen plan against the current receipt facts, locks every
     * referenced active receipt line in the deterministic order (receipt date,
     * external receipt line id, receipt id, stable UUID), recomputes
     * {@code remaining = confirmed - committed}, and fails the whole approval if
     * any line is missing, mismatched or short. No allocation is written here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ResolvedAllocation> resolveAndLockReceiptLines(
            UUID caseId, ReviewSnapshot snapshot, String purchaseOrderId, ApprovedAllocationPlan plan) {
        Map<ReceiptKey, ReceiptLineSnapshot> active = new LinkedHashMap<>();
        for (ReceiptLineSnapshot row : receiptLines.findByPurchaseOrderIdAndActiveTrue(purchaseOrderId)) {
            active.put(new ReceiptKey(row.receiptId(), row.receiptLineId()), row);
        }
        Map<String, LocalDate> receiptDateByReceipt = new LinkedHashMap<>();
        for (ReceiptSnapshot receipt : receiptSnapshots.findByPurchaseOrderIdAndActiveTrue(purchaseOrderId)) {
            receiptDateByReceipt.put(receipt.receiptId(), receipt.receiptDate());
        }

        List<ResolvedAllocation> resolved = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (PlannedReceiptAllocation planned : plan.allocations()) {
            ReceiptLineSnapshot row = active.get(new ReceiptKey(planned.receiptId(), planned.receiptLineId()));
            if (row == null) {
                mismatches.add("receipt line is not active: " + planned.receiptId() + "/" + planned.receiptLineId());
                continue;
            }
            LocalDate currentDate = receiptDateByReceipt.get(row.receiptId());
            if (!row.purchaseOrderId().equals(purchaseOrderId)
                    || !row.purchaseOrderLineId().equals(planned.purchaseOrderLineId())
                    || row.receiptLineVersion() != planned.receiptLineVersion()
                    || row.confirmedQuantity().value() != planned.confirmedQuantity()
                    || currentDate == null
                    || !currentDate.equals(planned.receiptDate())) {
                mismatches.add("frozen receipt fact does not match current purchasing facts: "
                        + planned.receiptId() + "/" + planned.receiptLineId());
                continue;
            }
            resolved.add(new ResolvedAllocation(
                    planned.invoiceLineNumber(), row, planned.receiptDate(), planned.plannedQuantity()));
        }
        if (!mismatches.isEmpty()) {
            throw new ApprovalNotPermittedException(
                    caseId, snapshot.id(), mismatches.stream().distinct().toList());
        }

        Map<UUID, LocalDate> receiptDateByRow = new LinkedHashMap<>();
        for (ResolvedAllocation allocation : resolved) {
            receiptDateByRow.putIfAbsent(allocation.row().id(), allocation.receiptDate());
        }

        List<ReceiptLineSnapshot> lockOrder = resolved.stream()
                .map(ResolvedAllocation::row)
                .distinct()
                .sorted(Comparator
                        .comparing((ReceiptLineSnapshot row) -> receiptDateByRow.get(row.id()))
                        .thenComparing(ReceiptLineSnapshot::receiptLineId)
                        .thenComparing(ReceiptLineSnapshot::receiptId)
                        .thenComparing(ReceiptLineSnapshot::id))
                .toList();

        Map<UUID, ReceiptLineSnapshot> locked = new LinkedHashMap<>();
        for (ReceiptLineSnapshot row : lockOrder) {
            ReceiptLineSnapshot lockedRow = receiptLines
                    .findByIdForUpdate(row.id())
                    .orElseThrow(() -> new ApprovalNotPermittedException(
                            caseId,
                            snapshot.id(),
                            List.of("referenced receipt line vanished while locking: " + row.receiptLineId())));
            locked.put(lockedRow.id(), lockedRow);
        }

        Map<UUID, Long> requested = new LinkedHashMap<>();
        for (ResolvedAllocation allocation : resolved) {
            requested.merge(allocation.row().id(), (long) allocation.plannedQuantity(), Long::sum);
        }

        List<ReceiptBalanceShortfall> shortfalls = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : requested.entrySet()) {
            ReceiptLineSnapshot row = locked.get(entry.getKey());
            long allocated = receiptAllocations.sumAllocatedQuantity(entry.getKey());
            long confirmed = row.confirmedQuantity().value();
            long remaining = confirmed - allocated;
            if (entry.getValue() > remaining) {
                shortfalls.add(new ReceiptBalanceShortfall(
                        row.receiptId(),
                        row.receiptLineId(),
                        confirmed,
                        allocated,
                        remaining,
                        entry.getValue()));
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new InsufficientReceiptBalanceException(caseId, snapshot.id(), shortfalls);
        }

        return resolved.stream()
                .map(allocation -> new ResolvedAllocation(
                        allocation.invoiceLineNumber(),
                        locked.get(allocation.row().id()),
                        allocation.receiptDate(),
                        allocation.plannedQuantity()))
                .toList();
    }

    private record ReceiptKey(String receiptId, String receiptLineId) {
    }

    record ResolvedAllocation(
            int invoiceLineNumber, ReceiptLineSnapshot row, LocalDate receiptDate, int plannedQuantity) {
    }
}
