package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.AnalysisRun;
import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    /**
     * Lower evidence versions of the same case that are still live reservations.
     * A newer submission stales exactly these; already stale runs are left
     * untouched.
     */
    List<AnalysisRun> findByInvoiceCaseIdAndStatusAndInputVersionLessThanOrderByInputVersionAsc(
            UUID invoiceCaseId, AnalysisRunStatus status, int inputVersion);
}
