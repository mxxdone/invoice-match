package com.invoicematch.core.purchasingreference.domain;

/**
 * A newer external purchasing snapshot would invalidate a receipt line that
 * already has committed local allocations: it drops the confirmed quantity below
 * the committed allocation sum or deactivates the line. The refresh is rejected
 * and rolled back so historical allocations keep their approved basis.
 */
public class ReceiptAllocationProtectedException extends PurchasingReferenceException {

    private final String purchaseOrderId;
    private final String receiptLineId;

    public ReceiptAllocationProtectedException(String purchaseOrderId, String receiptLineId, String detail) {
        super("External snapshot for purchase order " + purchaseOrderId + " cannot apply to receipt line "
                + receiptLineId + ": " + detail);
        this.purchaseOrderId = purchaseOrderId;
        this.receiptLineId = receiptLineId;
    }

    public String purchaseOrderId() {
        return purchaseOrderId;
    }

    public String receiptLineId() {
        return receiptLineId;
    }
}
