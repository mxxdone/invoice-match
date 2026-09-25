package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderLineSnapshot;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderLineSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshotRepository;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the local purchasing reference snapshot. It assembles the
 * current aggregate from active rows only; rows deactivated by a newer external
 * snapshot are excluded.
 */
@Component
public class PurchaseOrderSnapshotReader {

    private final PurchaseOrderSnapshotRepository snapshots;
    private final PurchaseOrderLineSnapshotRepository lines;
    private final ReceiptSnapshotRepository receipts;
    private final ReceiptLineSnapshotRepository receiptLines;

    public PurchaseOrderSnapshotReader(
            PurchaseOrderSnapshotRepository snapshots,
            PurchaseOrderLineSnapshotRepository lines,
            ReceiptSnapshotRepository receipts,
            ReceiptLineSnapshotRepository receiptLines) {
        this.snapshots = snapshots;
        this.lines = lines;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
    }

    @Transactional(readOnly = true)
    public Optional<PurchaseOrderAggregate> findCurrent(PurchaseOrderId purchaseOrderId) {
        String id = purchaseOrderId.value();
        Optional<PurchaseOrderSnapshot> snapshot = snapshots.findById(id);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }

        List<PurchaseOrderLineFacts> lineFacts = lines.findByPurchaseOrderIdAndActiveTrue(id).stream()
                .map(PurchaseOrderSnapshotReader::toLineFacts)
                .toList();

        Map<String, List<ReceiptLineFacts>> receiptLinesByReceipt =
                receiptLines.findByPurchaseOrderIdAndActiveTrue(id).stream()
                        .collect(Collectors.groupingBy(
                                ReceiptLineSnapshot::receiptId,
                                Collectors.mapping(PurchaseOrderSnapshotReader::toReceiptLineFacts, Collectors.toList())));

        List<ReceiptFacts> receiptFacts = receipts.findByPurchaseOrderIdAndActiveTrue(id).stream()
                .map(receipt -> new ReceiptFacts(
                        receipt.receiptId(),
                        receipt.status(),
                        receipt.receiptDate(),
                        receipt.receiptVersion(),
                        receiptLinesByReceipt.getOrDefault(receipt.receiptId(), List.of())))
                .toList();

        PurchaseOrderSnapshot root = snapshot.get();
        PurchaseOrderFacts purchaseOrder = new PurchaseOrderFacts(
                root.purchaseOrderVersion(),
                root.status(),
                SupplierId.of(root.supplierId()),
                root.supplierName(),
                lineFacts);

        return Optional.of(new PurchaseOrderAggregate(
                purchaseOrderId, root.snapshotVersion(), purchaseOrder, receiptFacts));
    }

    private static PurchaseOrderLineFacts toLineFacts(PurchaseOrderLineSnapshot row) {
        return new PurchaseOrderLineFacts(
                row.purchaseOrderLineId(), row.itemId(), row.itemName(), row.orderedQuantity(), row.unitPrice());
    }

    private static ReceiptLineFacts toReceiptLineFacts(ReceiptLineSnapshot row) {
        return new ReceiptLineFacts(
                row.receiptLineId(), row.receiptLineVersion(), row.purchaseOrderLineId(), row.confirmedQuantity());
    }
}
