package com.invoicematch.core.matching.persistence;

import com.invoicematch.core.matching.domain.MatchResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchResultRepository extends JpaRepository<MatchResult, UUID> {

    /**
     * Append-only history for one case, oldest first. The id is the final
     * tie-break so the order is total even for two results committed within the
     * same clock tick.
     */
    List<MatchResult> findByInvoiceCaseIdOrderByCreatedAtAscIdAsc(UUID invoiceCaseId);

    /** The most recently persisted result for one case, if any. */
    Optional<MatchResult> findFirstByInvoiceCaseIdOrderByCreatedAtDescIdDesc(UUID invoiceCaseId);
}
