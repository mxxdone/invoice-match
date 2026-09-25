package com.invoicematch.core.purchasingreference.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderLineSnapshotRepository extends JpaRepository<PurchaseOrderLineSnapshot, UUID> {

    /** All rows, including inactive, for in-place upsert bookkeeping. */
    List<PurchaseOrderLineSnapshot> findByPurchaseOrderId(String purchaseOrderId);

    /** Current facts only, for reads. */
    List<PurchaseOrderLineSnapshot> findByPurchaseOrderIdAndActiveTrue(String purchaseOrderId);
}
