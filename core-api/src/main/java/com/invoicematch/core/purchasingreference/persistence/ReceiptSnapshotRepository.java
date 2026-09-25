package com.invoicematch.core.purchasingreference.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptSnapshotRepository extends JpaRepository<ReceiptSnapshot, UUID> {

    List<ReceiptSnapshot> findByPurchaseOrderId(String purchaseOrderId);

    @Modifying
    @Query("delete from ReceiptSnapshot r where r.purchaseOrderId = :purchaseOrderId")
    void deleteByPurchaseOrderId(@Param("purchaseOrderId") String purchaseOrderId);
}
