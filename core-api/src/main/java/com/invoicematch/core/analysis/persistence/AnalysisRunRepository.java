package com.invoicematch.core.analysis.persistence;

import com.invoicematch.core.analysis.domain.AnalysisRun;
import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    /**
     * Lower evidence versions of the same case that are not yet stale. A newer
     * submission stales exactly these regardless of whether the run is still
     * queued, running, or already finished; the terminal results are preserved.
     */
    List<AnalysisRun> findByInvoiceCaseIdAndStatusInAndInputVersionLessThanOrderByInputVersionAsc(
            UUID invoiceCaseId, Collection<AnalysisRunStatus> statuses, int inputVersion);
}
