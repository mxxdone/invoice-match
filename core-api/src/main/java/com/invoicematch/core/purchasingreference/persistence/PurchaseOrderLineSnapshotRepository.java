package com.invoicematch.core.purchasingreference.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderLineSnapshotRepository extends JpaRepository<PurchaseOrderLineSnapshot, UUID> {

    List<PurchaseOrderLineSnapshot> findByPurchaseOrderId(String purchaseOrderId);

    @Modifying
    @Query("delete from PurchaseOrderLineSnapshot l where l.purchaseOrderId = :purchaseOrderId")
    void deleteByPurchaseOrderId(@Param("purchaseOrderId") String purchaseOrderId);
}
