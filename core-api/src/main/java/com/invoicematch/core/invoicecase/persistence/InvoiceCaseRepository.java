package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceCaseRepository extends JpaRepository<InvoiceCase, UUID> {
}
