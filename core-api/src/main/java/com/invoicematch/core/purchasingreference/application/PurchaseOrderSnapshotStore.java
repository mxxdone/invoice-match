package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.purchasingreference.domain.ExternalSnapshotConflictException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptAllocationProtectedException;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.RefreshOutcome;
import com.invoicematch.core.purchasingreference.domain.RefreshResult;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderLineSnapshot;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderLineSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshotRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one fetched external aggregate snapshot to the local current
 * snapshot inside a single transaction. The external HTTP call has already
 * completed before this store is invoked, so no lock is held during it.
 *
 * <p>Child rows are keyed by their stable external identifiers and updated in
 * place, never deleted and re-created. New children are inserted and children
 * missing from a newer snapshot are deactivated, preserving row identity for
 * future foreign keys and row locks.
 *
 * <p>Version rules: a newer snapshot version replaces the stored data; a lower
 * version is ignored and reported as stale; the same version with an identical
 * canonical payload is a no-op; the same version with a different payload is an
 * external contract conflict and leaves the database untouched.
 */
@Component
public class PurchaseOrderSnapshotStore {

    private final PurchaseOrderSnapshotRepository snapshots;
    private final PurchaseOrderLineSnapshotRepository lines;
    private final ReceiptSnapshotRepository receipts;
    private final ReceiptLineSnapshotRepository receiptLines;
    private final PurchaseOrderSnapshotLock purchaseOrderLock;
    private final ReceiptAllocationCommitmentReader allocationCommitments;

    public PurchaseOrderSnapshotStore(
            PurchaseOrderSnapshotRepository snapshots,
            PurchaseOrderLineSnapshotRepository lines,
            ReceiptSnapshotRepository receipts,
            ReceiptLineSnapshotRepository receiptLines,
            PurchaseOrderSnapshotLock purchaseOrderLock,
            ReceiptAllocationCommitmentReader allocationCommitments) {
        this.snapshots = snapshots;
        this.lines = lines;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
        this.purchaseOrderLock = purchaseOrderLock;
        this.allocationCommitments = allocationCommitments;
    }

    @Transactional
    public RefreshResult apply(
            PurchaseOrderAggregate aggregate, String canonicalPayload, String payloadHash, Instant retrievedAt) {
        String purchaseOrderId = aggregate.purchaseOrderId().value();
        purchaseOrderLock.acquireXactLock(purchaseOrderId);
        var existing = snapshots.findForUpdate(purchaseOrderId);

        PurchaseOrderFacts purchaseOrder = aggregate.purchaseOrder();
        if (existing.isEmpty()) {
            snapshots.save(PurchaseOrderSnapshot.create(
                    purchaseOrderId,
                    purchaseOrder.supplierId().value(),
                    purchaseOrder.supplierName(),
                    purchaseOrder.status(),
                    aggregate.snapshotVersion(),
                    purchaseOrder.version(),
                    payloadHash,
                    canonicalPayload,
                    retrievedAt));
            upsertChildren(purchaseOrderId, aggregate);
            return RefreshResult.of(RefreshOutcome.CREATED, aggregate.snapshotVersion());
        }

        PurchaseOrderSnapshot snapshot = existing.get();
        long storedVersion = snapshot.snapshotVersion();
        long incomingVersion = aggregate.snapshotVersion();

        if (incomingVersion < storedVersion) {
            return RefreshResult.of(RefreshOutcome.STALE_IGNORED, storedVersion);
        }
        if (incomingVersion == storedVersion) {
            if (snapshot.payloadHash().equals(payloadHash)) {
                return RefreshResult.of(RefreshOutcome.UNCHANGED, storedVersion);
            }
            throw new ExternalSnapshotConflictException(purchaseOrderId, storedVersion);
        }

        snapshot.replaceWith(
                purchaseOrder.supplierId().value(),
                purchaseOrder.supplierName(),
                purchaseOrder.status(),
                incomingVersion,
                purchaseOrder.version(),
                payloadHash,
                canonicalPayload,
                retrievedAt);
        upsertChildren(purchaseOrderId, aggregate);
        return RefreshResult.of(RefreshOutcome.UPDATED, incomingVersion);
    }

    private void upsertChildren(String purchaseOrderId, PurchaseOrderAggregate aggregate) {
        upsertLines(purchaseOrderId, aggregate);
        upsertReceipts(purchaseOrderId, aggregate);
        upsertReceiptLines(purchaseOrderId, aggregate);
    }

    private void upsertLines(String purchaseOrderId, PurchaseOrderAggregate aggregate) {
        Map<String, PurchaseOrderLineSnapshot> existing = new HashMap<>();
        for (PurchaseOrderLineSnapshot row : lines.findByPurchaseOrderId(purchaseOrderId)) {
            existing.put(row.purchaseOrderLineId(), row);
        }
        Set<String> incoming = new HashSet<>();
        for (var facts : aggregate.purchaseOrder().lines()) {
            PurchaseOrderLineSnapshot row = existing.get(facts.purchaseOrderLineId());
            if (row == null) {
                lines.save(PurchaseOrderLineSnapshot.create(UUID.randomUUID(), purchaseOrderId, facts));
            } else {
                row.updateFrom(facts);
            }
            incoming.add(facts.purchaseOrderLineId());
        }
        for (PurchaseOrderLineSnapshot row : existing.values()) {
            if (!incoming.contains(row.purchaseOrderLineId())) {
                row.deactivate();
            }
        }
        lines.flush();
    }

    private void upsertReceipts(String purchaseOrderId, PurchaseOrderAggregate aggregate) {
        Map<String, ReceiptSnapshot> existing = new HashMap<>();
        for (ReceiptSnapshot row : receipts.findByPurchaseOrderId(purchaseOrderId)) {
            existing.put(row.receiptId(), row);
        }
        Set<String> incoming = new HashSet<>();
        for (ReceiptFacts facts : aggregate.receipts()) {
            ReceiptSnapshot row = existing.get(facts.receiptId());
            if (row == null) {
                receipts.save(ReceiptSnapshot.create(UUID.randomUUID(), purchaseOrderId, facts));
            } else {
                row.updateFrom(facts);
            }
            incoming.add(facts.receiptId());
        }
        for (ReceiptSnapshot row : existing.values()) {
            if (!incoming.contains(row.receiptId())) {
                row.deactivate();
            }
        }
        receipts.flush();
    }

    private void upsertReceiptLines(String purchaseOrderId, PurchaseOrderAggregate aggregate) {
        Map<ReceiptLineKey, ReceiptLineSnapshot> existing = new HashMap<>();
        for (ReceiptLineSnapshot row : receiptLines.findByPurchaseOrderId(purchaseOrderId)) {
            existing.put(new ReceiptLineKey(row.receiptId(), row.receiptLineId()), row);
        }
        Set<ReceiptLineKey> incoming = new HashSet<>();
        for (ReceiptFacts receipt : aggregate.receipts()) {
            for (ReceiptLineFacts facts : receipt.lines()) {
                ReceiptLineKey key = new ReceiptLineKey(receipt.receiptId(), facts.receiptLineId());
                ReceiptLineSnapshot row = existing.get(key);
                if (row == null) {
                    receiptLines.save(
                            ReceiptLineSnapshot.create(UUID.randomUUID(), purchaseOrderId, receipt.receiptId(), facts));
                } else {
                    long committed = allocationCommitments.committedQuantity(row.id());
                    if (facts.confirmedQuantity().value() < committed) {
                        throw new ReceiptAllocationProtectedException(
                                purchaseOrderId,
                                facts.receiptLineId(),
                                "confirmed quantity " + facts.confirmedQuantity().value()
                                        + " is below the committed allocation " + committed);
                    }
                    row.updateFrom(facts);
                }
                incoming.add(key);
            }
        }
        for (ReceiptLineSnapshot row : existing.values()) {
            if (!incoming.contains(new ReceiptLineKey(row.receiptId(), row.receiptLineId()))) {
                long committed = allocationCommitments.committedQuantity(row.id());
                if (committed > 0) {
                    throw new ReceiptAllocationProtectedException(
                            purchaseOrderId,
                            row.receiptLineId(),
                            "the receipt line has committed allocations and cannot be deactivated");
                }
                row.deactivate();
            }
        }
        receiptLines.flush();
    }

    /**
     * Structured composite key so that receipt ids and receipt line ids that
     * merely concatenate to the same string cannot collide.
     */
    private record ReceiptLineKey(String receiptId, String receiptLineId) {
    }
}
