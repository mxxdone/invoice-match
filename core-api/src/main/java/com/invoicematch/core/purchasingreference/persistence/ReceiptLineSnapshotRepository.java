package com.invoicematch.core.purchasingreference.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptLineSnapshotRepository extends JpaRepository<ReceiptLineSnapshot, UUID> {

    /** All rows, including inactive, for in-place upsert bookkeeping. */
    List<ReceiptLineSnapshot> findByPurchaseOrderId(String purchaseOrderId);

    /** Current facts only, for reads. */
    List<ReceiptLineSnapshot> findByPurchaseOrderIdAndActiveTrue(String purchaseOrderId);

    /**
     * Locks one receipt line while its confirmed balance is consumed by
     * approval. Callers must acquire these row locks in the deterministic
     * (receipt date, external receipt line id, receipt id, id) order so two
     * approvals sharing receipt lines cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReceiptLineSnapshot r where r.id = :id")
    Optional<ReceiptLineSnapshot> findByIdForUpdate(@Param("id") UUID id);
}
