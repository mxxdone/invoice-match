package com.invoicematch.core.purchasingreference.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderSnapshotRepository extends JpaRepository<PurchaseOrderSnapshot, String> {

    /**
     * Loads the current snapshot while holding a write lock so concurrent
     * refreshes for the same purchase order are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PurchaseOrderSnapshot s where s.purchaseOrderId = :purchaseOrderId")
    Optional<PurchaseOrderSnapshot> findForUpdate(@Param("purchaseOrderId") String purchaseOrderId);
}
