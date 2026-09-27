package com.invoicematch.core.approval.persistence;

import com.invoicematch.core.purchasingreference.application.ReceiptAllocationCommitmentReader;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapter exposing the committed allocation sum of one receipt line to the
 * purchasing reference store, so a later refresh can be refused before it
 * invalidates an approved allocation.
 */
@Component
public class ReceiptAllocationCommitmentAdapter implements ReceiptAllocationCommitmentReader {

    private final ReceiptAllocationRepository allocations;

    public ReceiptAllocationCommitmentAdapter(ReceiptAllocationRepository allocations) {
        this.allocations = allocations;
    }

    @Override
    public long committedQuantity(UUID receiptLineSnapshotId) {
        return allocations.sumAllocatedQuantity(receiptLineSnapshotId);
    }
}
