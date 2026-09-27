package com.invoicematch.core.purchasingreference.application;

import java.util.UUID;

/**
 * Read port for the committed local receipt allocation quantity. The purchasing
 * reference store uses it before it applies a newer external snapshot so it can
 * refuse to reduce a confirmed quantity below, or deactivate, a receipt line
 * that already has approved allocations. It keeps the purchasing package free of
 * a direct dependency on the approval package.
 */
public interface ReceiptAllocationCommitmentReader {

    long committedQuantity(UUID receiptLineSnapshotId);
}
