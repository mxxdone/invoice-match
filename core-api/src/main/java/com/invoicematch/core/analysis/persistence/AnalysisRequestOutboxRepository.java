package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.AnalysisRequestOutbox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRequestOutboxRepository extends JpaRepository<AnalysisRequestOutbox, UUID> {

    Optional<AnalysisRequestOutbox> findByAnalysisRunId(UUID analysisRunId);
}
