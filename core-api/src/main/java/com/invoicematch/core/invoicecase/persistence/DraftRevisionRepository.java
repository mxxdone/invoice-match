package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.DraftRevisionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftRevisionRepository extends JpaRepository<DraftRevision, UUID> {

    Optional<DraftRevision> findByInvoiceCaseIdAndStatus(UUID invoiceCaseId, DraftRevisionStatus status);

    Optional<DraftRevision> findFirstByInvoiceCaseIdOrderByRevisionNumberDesc(UUID invoiceCaseId);
}
