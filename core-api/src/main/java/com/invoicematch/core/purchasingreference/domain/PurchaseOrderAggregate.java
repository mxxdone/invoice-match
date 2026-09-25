package com.invoicematch.core.purchasingreference.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import java.util.List;
import java.util.Objects;

/**
 * One complete external purchase order aggregate snapshot: the purchase order
 * header and lines plus all receipts and receipt lines, tied together by a
 * single monotonic {@code snapshotVersion} that increases whenever any included
 * external fact changes.
 */
public record PurchaseOrderAggregate(
        PurchaseOrderId purchaseOrderId,
        long snapshotVersion,
        PurchaseOrderFacts purchaseOrder,
        List<ReceiptFacts> receipts) {

    public PurchaseOrderAggregate {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
        if (snapshotVersion < 0) {
            throw new DomainValidationException("snapshotVersion must not be negative: " + snapshotVersion);
        }
        Objects.requireNonNull(purchaseOrder, "purchaseOrder");
        receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
    }
}
