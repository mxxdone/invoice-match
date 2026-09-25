package com.invoicematch.core.purchasingreference.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.Quantity;
import java.util.Objects;

/**
 * A purchase order line fact from the external purchasing system. The external
 * line identifier is stable and is what receipt lines reference.
 */
public record PurchaseOrderLineFacts(
        String purchaseOrderLineId,
        String itemId,
        String itemName,
        Quantity orderedQuantity,
        Money unitPrice) {

    public PurchaseOrderLineFacts {
        purchaseOrderLineId = requireText(purchaseOrderLineId, "purchaseOrderLineId");
        itemId = requireText(itemId, "itemId");
        itemName = requireText(itemName, "itemName");
        Objects.requireNonNull(orderedQuantity, "orderedQuantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        return value;
    }
}
