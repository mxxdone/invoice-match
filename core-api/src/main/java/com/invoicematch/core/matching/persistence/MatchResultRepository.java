package com.invoicematch.core.matching.persistence;

import com.invoicematch.core.matching.domain.MatchResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchResultRepository extends JpaRepository<MatchResult, UUID> {

    /**
     * Append-only history for one case, oldest first. Ordering by the
     * per-case monotonic result number makes the order total and independent of
     * timestamp resolution or concurrent commits.
     */
    List<MatchResult> findByInvoiceCaseIdOrderByResultNumberAsc(UUID invoiceCaseId);

    /** The most recently persisted result for one case, if any. */
    Optional<MatchResult> findFirstByInvoiceCaseIdOrderByResultNumberDesc(UUID invoiceCaseId);

    /** Highest result number allocated for one case, or zero when none exists. */
    @Query("select coalesce(max(r.resultNumber), 0) from MatchResult r where r.invoiceCaseId = :caseId")
    int maxResultNumber(@Param("caseId") UUID caseId);
}
