package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.DraftRevision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftRevisionRepository extends JpaRepository<DraftRevision, UUID> {
}
