package com.invoicematch.core.approval.persistence;

import com.invoicematch.core.approval.domain.ReceiptAllocation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptAllocationRepository extends JpaRepository<ReceiptAllocation, UUID> {

    /**
     * Running allocated quantity of one receipt line. Read after the caller has
     * locked the receipt line row so concurrent allocators are serialized.
     */
    @Query("select coalesce(sum(a.allocatedQuantity), 0) from ReceiptAllocation a"
            + " where a.receiptLineSnapshotId = :receiptLineSnapshotId")
    long sumAllocatedQuantity(@Param("receiptLineSnapshotId") UUID receiptLineSnapshotId);

    List<ReceiptAllocation> findByReviewSnapshotId(UUID reviewSnapshotId);
}
