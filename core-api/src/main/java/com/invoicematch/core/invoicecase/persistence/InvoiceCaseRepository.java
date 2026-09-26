package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceCaseRepository extends JpaRepository<InvoiceCase, UUID> {

    /**
     * Loads the case with a row lock so concurrent writes on the same case are
     * serialized and the {@code expectedCaseVersion} check observes the latest
     * committed version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InvoiceCase c where c.id = :id")
    Optional<InvoiceCase> findByIdForUpdate(@Param("id") UUID id);
}
