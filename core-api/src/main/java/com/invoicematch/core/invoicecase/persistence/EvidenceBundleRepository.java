package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvidenceBundleRepository extends JpaRepository<EvidenceBundle, UUID> {

    List<EvidenceBundle> findByInvoiceCaseIdOrderByVersionNumberAsc(UUID invoiceCaseId);

    Optional<EvidenceBundle> findByInvoiceCaseIdAndVersionNumber(UUID invoiceCaseId, int versionNumber);

    Optional<EvidenceBundle> findFirstByInvoiceCaseIdOrderByVersionNumberDesc(UUID invoiceCaseId);

    @Query("select coalesce(max(b.versionNumber), 0) from EvidenceBundle b where b.invoiceCaseId = :caseId")
    int maxVersionNumber(@Param("caseId") UUID caseId);
}
