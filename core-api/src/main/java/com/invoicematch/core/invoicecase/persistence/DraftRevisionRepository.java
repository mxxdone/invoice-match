package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.DraftRevisionStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DraftRevisionRepository extends JpaRepository<DraftRevision, UUID> {

    Optional<DraftRevision> findByInvoiceCaseIdAndStatus(UUID invoiceCaseId, DraftRevisionStatus status);

    Optional<DraftRevision> findFirstByInvoiceCaseIdOrderByRevisionNumberDesc(UUID invoiceCaseId);

    /**
     * Locks the draft revision row ({@code SELECT ... FOR UPDATE}). The row is
     * the shared mutex with the invoice-line immutability trigger: submission
     * takes this lock before reading lines or computing the canonical hash, and
     * a concurrent line mutation takes the same lock before checking status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DraftRevision r where r.id = :id")
    Optional<DraftRevision> findByIdForUpdate(@Param("id") UUID id);
}
