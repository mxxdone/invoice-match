package com.invoicematch.core.purchasingreference.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptSnapshotRepository extends JpaRepository<ReceiptSnapshot, UUID> {

    /** All rows, including inactive, for in-place upsert bookkeeping. */
    List<ReceiptSnapshot> findByPurchaseOrderId(String purchaseOrderId);

    /** Current facts only, for reads. */
    List<ReceiptSnapshot> findByPurchaseOrderIdAndActiveTrue(String purchaseOrderId);
}
