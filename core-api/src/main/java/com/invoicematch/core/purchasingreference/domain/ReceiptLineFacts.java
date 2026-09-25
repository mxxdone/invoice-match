package com.invoicematch.core.purchasingreference.domain;

import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import com.invoicematch.core.shared.domain.DomainValidationException;
import java.util.Objects;

/**
 * A receipt line fact from the external purchasing system. It carries the
 * externally confirmed quantity, which is distinct from any local allocation
 * quantity, and a reference to the purchase order line it received.
 */
public record ReceiptLineFacts(
        String receiptLineId,
        long version,
        String purchaseOrderLineId,
        ConfirmedQuantity confirmedQuantity) {

    public ReceiptLineFacts {
        receiptLineId = PurchaseOrderLineFacts.requireText(receiptLineId, "receiptLineId");
        if (version < 0) {
            throw new DomainValidationException("receiptLine version must not be negative: " + version);
        }
        purchaseOrderLineId = PurchaseOrderLineFacts.requireText(purchaseOrderLineId, "purchaseOrderLineId");
        Objects.requireNonNull(confirmedQuantity, "confirmedQuantity");
    }
}
