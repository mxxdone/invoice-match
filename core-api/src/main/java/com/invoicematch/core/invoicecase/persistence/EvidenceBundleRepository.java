package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceBundleRepository extends JpaRepository<EvidenceBundle, UUID> {
}
