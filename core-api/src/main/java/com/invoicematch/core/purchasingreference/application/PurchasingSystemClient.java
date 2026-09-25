package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.shared.domain.PurchaseOrderId;

/**
 * Read-only port onto the external purchasing system of record. Implementations
 * translate transport and parsing failures into
 * {@link com.invoicematch.core.purchasingreference.domain.PurchasingReferenceException}
 * subtypes.
 */
public interface PurchasingSystemClient {

    PurchaseOrderAggregate fetch(PurchaseOrderId purchaseOrderId);
}
