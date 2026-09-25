package com.invoicematch.core.purchasingreference.domain;

/**
 * The external purchasing system does not know the requested purchase order
 * (external HTTP 404).
 */
public class PurchaseOrderNotFoundException extends PurchasingReferenceException {

    private final String purchaseOrderId;

    public PurchaseOrderNotFoundException(String purchaseOrderId) {
        super("Purchase order not found in external purchasing system: " + purchaseOrderId);
        this.purchaseOrderId = purchaseOrderId;
    }

    public String purchaseOrderId() {
        return purchaseOrderId;
    }
}
