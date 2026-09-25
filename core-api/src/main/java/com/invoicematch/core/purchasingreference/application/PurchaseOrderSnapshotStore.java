package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.purchasingreference.domain.ExternalSnapshotConflictException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderFacts;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one fetched external aggregate snapshot to the local current
 * snapshot inside a single transaction. The external HTTP call has already
 * completed before this store is invoked, so no lock is held during it.
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

    public PurchaseOrderSnapshotStore(
            PurchaseOrderSnapshotRepository snapshots,
            PurchaseOrderLineSnapshotRepository lines,
            ReceiptSnapshotRepository receipts,
            ReceiptLineSnapshotRepository receiptLines) {
        this.snapshots = snapshots;
        this.lines = lines;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
    }

    @Transactional
    public RefreshResult apply(
            PurchaseOrderAggregate aggregate, String canonicalPayload, String payloadHash, Instant retrievedAt) {
        String purchaseOrderId = aggregate.purchaseOrderId().value();
        var existing = snapshots.findForUpdate(purchaseOrderId);

        if (existing.isEmpty()) {
            PurchaseOrderFacts purchaseOrder = aggregate.purchaseOrder();
            snapshots.save(PurchaseOrderSnapshot.create(
                    purchaseOrderId,
                    purchaseOrder.supplierId().value(),
                    purchaseOrder.supplierName(),
                    purchaseOrder.status(),
                    aggregate.snapshotVersion(),
                    payloadHash,
                    canonicalPayload,
                    retrievedAt));
            replaceChildren(purchaseOrderId, aggregate);
            return RefreshResult.of(RefreshOutcome.CREATED, aggregate.snapshotVersion());
        }

        PurchaseOrderSnapshot snapshot = existing.get();
        long storedVersion = snapshot.externalVersion();
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

        PurchaseOrderFacts purchaseOrder = aggregate.purchaseOrder();
        deleteChildren(purchaseOrderId);
        snapshot.replaceWith(
                purchaseOrder.supplierId().value(),
                purchaseOrder.supplierName(),
                purchaseOrder.status(),
                incomingVersion,
                payloadHash,
                canonicalPayload,
                retrievedAt);
        replaceChildren(purchaseOrderId, aggregate);
        return RefreshResult.of(RefreshOutcome.UPDATED, incomingVersion);
    }

    private void deleteChildren(String purchaseOrderId) {
        receiptLines.deleteByPurchaseOrderId(purchaseOrderId);
        receipts.deleteByPurchaseOrderId(purchaseOrderId);
        lines.deleteByPurchaseOrderId(purchaseOrderId);
    }

    private void replaceChildren(String purchaseOrderId, PurchaseOrderAggregate aggregate) {
        List<PurchaseOrderLineSnapshot> lineRows = new ArrayList<>();
        for (var line : aggregate.purchaseOrder().lines()) {
            lineRows.add(PurchaseOrderLineSnapshot.create(UUID.randomUUID(), purchaseOrderId, line));
        }

        List<ReceiptSnapshot> receiptRows = new ArrayList<>();
        List<ReceiptLineSnapshot> receiptLineRows = new ArrayList<>();
        for (ReceiptFacts receipt : aggregate.receipts()) {
            receiptRows.add(ReceiptSnapshot.create(UUID.randomUUID(), purchaseOrderId, receipt));
            for (ReceiptLineFacts line : receipt.lines()) {
                receiptLineRows.add(
                        ReceiptLineSnapshot.create(UUID.randomUUID(), purchaseOrderId, receipt.receiptId(), line));
            }
        }

        lines.saveAll(lineRows);
        lines.flush();
        receipts.saveAll(receiptRows);
        receipts.flush();
        receiptLines.saveAll(receiptLineRows);
        receiptLines.flush();
    }
}
