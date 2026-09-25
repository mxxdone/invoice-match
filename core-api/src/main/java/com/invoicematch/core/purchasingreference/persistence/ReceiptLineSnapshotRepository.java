package com.invoicematch.core.purchasingreference.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptLineSnapshotRepository extends JpaRepository<ReceiptLineSnapshot, UUID> {

    /** All rows, including inactive, for in-place upsert bookkeeping. */
    List<ReceiptLineSnapshot> findByPurchaseOrderId(String purchaseOrderId);

    /** Current facts only, for reads. */
    List<ReceiptLineSnapshot> findByPurchaseOrderIdAndActiveTrue(String purchaseOrderId);
}
