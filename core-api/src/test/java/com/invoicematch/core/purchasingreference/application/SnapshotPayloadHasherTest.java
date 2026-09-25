package com.invoicematch.core.purchasingreference.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderStatus;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotPayloadHasherTest {

    private final SnapshotPayloadHasher hasher = new SnapshotPayloadHasher();

    @Test
    void isIndependentOfCollectionOrdering() {
        PurchaseOrderLineFacts line1 = line("POL-1", 100, 2500);
        PurchaseOrderLineFacts line2 = line("POL-2", 20, 55000);
        ReceiptLineFacts receiptLine =
                new ReceiptLineFacts("RCL-1", 2, "POL-1", ConfirmedQuantity.of(60));
        ReceiptFacts receipt = receipt("RCV-1", receiptLine);

        PurchaseOrderAggregate ordered = aggregate(5, List.of(line1, line2), List.of(receipt));
        PurchaseOrderAggregate reordered = aggregate(5, List.of(line2, line1), List.of(receipt));

        assertThat(hasher.canonicalJson(reordered)).isEqualTo(hasher.canonicalJson(ordered));
        assertThat(hasher.sha256Hex(hasher.canonicalJson(reordered)))
                .isEqualTo(hasher.sha256Hex(hasher.canonicalJson(ordered)));
    }

    @Test
    void changesWhenAnyFactChanges() {
        PurchaseOrderAggregate before = aggregate(5, List.of(line("POL-1", 100, 2500)), List.of());
        PurchaseOrderAggregate after = aggregate(5, List.of(line("POL-1", 101, 2500)), List.of());

        assertThat(hasher.sha256Hex(hasher.canonicalJson(after)))
                .isNotEqualTo(hasher.sha256Hex(hasher.canonicalJson(before)));
    }

    private static PurchaseOrderAggregate aggregate(
            long snapshotVersion, List<PurchaseOrderLineFacts> lines, List<ReceiptFacts> receipts) {
        return new PurchaseOrderAggregate(
                PurchaseOrderId.of("PO-1"),
                snapshotVersion,
                new PurchaseOrderFacts(
                        3, PurchaseOrderStatus.CONFIRMED, SupplierId.of("SUP-1"), "Hanul Office Supply", lines),
                receipts);
    }

    private static PurchaseOrderLineFacts line(String id, int orderedQuantity, long unitPrice) {
        return new PurchaseOrderLineFacts(id, "ITEM-" + id, "Item " + id, Quantity.of(orderedQuantity), Money.of(unitPrice));
    }

    private static ReceiptFacts receipt(String id, ReceiptLineFacts... lines) {
        return new ReceiptFacts(id, ReceiptStatus.CONFIRMED, LocalDate.of(2026, 1, 5), 2, List.of(lines));
    }
}
