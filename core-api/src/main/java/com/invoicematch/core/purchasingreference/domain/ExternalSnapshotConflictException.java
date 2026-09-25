package com.invoicematch.core.purchasingreference.domain;

/**
 * The external system reported the same aggregate snapshot version as the one
 * stored locally but with a different canonical payload. The same version must
 * always describe the same facts, so this is an external contract conflict and
 * no local data is changed.
 */
public class ExternalSnapshotConflictException extends PurchasingReferenceException {

    private final String purchaseOrderId;
    private final long version;

    public ExternalSnapshotConflictException(String purchaseOrderId, long version) {
        super("External snapshot conflict for purchase order " + purchaseOrderId + " at version " + version
                + ": same version with a different payload");
        this.purchaseOrderId = purchaseOrderId;
        this.version = version;
    }

    public String purchaseOrderId() {
        return purchaseOrderId;
    }

    public long version() {
        return version;
    }
}
