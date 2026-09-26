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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the local purchasing reference snapshot. It assembles the
 * current aggregate from active rows only; rows deactivated by a newer external
 * snapshot are excluded.
 *
 * <p>The read always runs in its own {@link Isolation#REPEATABLE_READ}
 * transaction ({@link Propagation#REQUIRES_NEW}) so that the root and child
 * statements observe one consistent PostgreSQL snapshot regardless of the
 * caller's transaction or isolation level. A refresh that commits between two
 * child queries can therefore never produce a hybrid of the old and new
 * snapshot versions.
 */
@Component
public class PurchaseOrderSnapshotReader {

    private final PurchaseOrderSnapshotRepository snapshots;
    private final PurchaseOrderLineSnapshotRepository lines;
    private final ReceiptSnapshotRepository receipts;
    private final ReceiptLineSnapshotRepository receiptLines;
    private final SnapshotReadInterceptor interceptor;

    PurchaseOrderSnapshotReader(
            PurchaseOrderSnapshotRepository snapshots,
            PurchaseOrderLineSnapshotRepository lines,
            ReceiptSnapshotRepository receipts,
            ReceiptLineSnapshotRepository receiptLines,
            ObjectProvider<SnapshotReadInterceptor> interceptors) {
        this.snapshots = snapshots;
        this.lines = lines;
        this.receipts = receipts;
        this.receiptLines = receiptLines;
        this.interceptor = interceptors.getIfAvailable(() -> SnapshotReadInterceptor.NONE);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Optional<PurchaseOrderAggregate> findCurrent(PurchaseOrderId purchaseOrderId) {
        String id = purchaseOrderId.value();
        Optional<PurchaseOrderSnapshot> snapshot = snapshots.findById(id);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        interceptor.afterRootLoaded();

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
