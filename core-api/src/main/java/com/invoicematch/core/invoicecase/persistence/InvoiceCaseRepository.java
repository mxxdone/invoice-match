package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import jakarta.persistence.LockModeType;
import java.util.List;
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

    /**
     * Finds the identifiers of every other case that shares the same supplier
     * and normalized invoice number. Used for the business duplicate exception
     * only: matching never rejects storage and this is unrelated to technical
     * request-id idempotency.
     */
    @Query("select c.id from InvoiceCase c"
            + " where c.supplierId = :supplierId"
            + " and c.normalizedInvoiceNumber = :normalizedInvoiceNumber"
            + " and c.id <> :excludingCaseId"
            + " order by c.id")
    List<UUID> findOtherCaseIdsByBusinessInvoice(
            @Param("supplierId") String supplierId,
            @Param("normalizedInvoiceNumber") String normalizedInvoiceNumber,
            @Param("excludingCaseId") UUID excludingCaseId);
}
