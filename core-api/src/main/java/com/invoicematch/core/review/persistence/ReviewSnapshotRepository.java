package com.invoicematch.core.review.persistence;

import com.invoicematch.core.review.domain.ReviewSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewSnapshotRepository extends JpaRepository<ReviewSnapshot, UUID> {

    /**
     * Append-only history for one case, oldest first. Ordering by the per-case
     * monotonic snapshot number makes the order total and independent of
     * timestamp resolution or concurrent commits.
     */
    List<ReviewSnapshot> findByInvoiceCaseIdOrderBySnapshotNumberAsc(UUID invoiceCaseId);

    /** The most recently frozen snapshot for one case, if any. */
    Optional<ReviewSnapshot> findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(UUID invoiceCaseId);

    /** One snapshot of a case by its business order. */
    Optional<ReviewSnapshot> findByInvoiceCaseIdAndSnapshotNumber(UUID invoiceCaseId, int snapshotNumber);

    /** Highest snapshot number allocated for one case, or zero when none exists. */
    @Query("select coalesce(max(s.snapshotNumber), 0) from ReviewSnapshot s where s.invoiceCaseId = :caseId")
    int maxSnapshotNumber(@Param("caseId") UUID caseId);
}
