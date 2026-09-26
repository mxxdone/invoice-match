package com.invoicematch.core.review.persistence;

import com.invoicematch.core.review.domain.ReviewDecision;
import com.invoicematch.core.review.domain.ReviewDecisionType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {

    /**
     * Append-only decision history for one case, oldest first, ordered by the
     * per-case monotonic decision number.
     */
    List<ReviewDecision> findByInvoiceCaseIdOrderByDecisionNumberAsc(UUID invoiceCaseId);

    Optional<ReviewDecision> findFirstByInvoiceCaseIdOrderByDecisionNumberDesc(UUID invoiceCaseId);

    /**
     * Every mapping decision recorded against one exact evidence bundle, oldest
     * first. The service folds them into the latest decision per invoice line to
     * resolve the effective mappings for a bundle.
     */
    @Query("select d from ReviewDecision d"
            + " where d.invoiceCaseId = :caseId"
            + " and d.decision = :type"
            + " and d.mappingBundleId = :bundleId"
            + " order by d.decisionNumber asc")
    List<ReviewDecision> findMappingDecisionsForBundle(
            @Param("caseId") UUID caseId,
            @Param("bundleId") UUID bundleId,
            @Param("type") ReviewDecisionType type);

    /**
     * Highest mapping decision number recorded against one bundle, or zero. It
     * is the bundle-scoped mapping watermark a match result and snapshot carry.
     */
    @Query("select coalesce(max(d.decisionNumber), 0) from ReviewDecision d"
            + " where d.invoiceCaseId = :caseId"
            + " and d.decision = :type"
            + " and d.mappingBundleId = :bundleId")
    int maxMappingWatermark(
            @Param("caseId") UUID caseId,
            @Param("bundleId") UUID bundleId,
            @Param("type") ReviewDecisionType type);

    /** Highest decision number allocated for one case, or zero when none exists. */
    @Query("select coalesce(max(d.decisionNumber), 0) from ReviewDecision d where d.invoiceCaseId = :caseId")
    int maxDecisionNumber(@Param("caseId") UUID caseId);
}
