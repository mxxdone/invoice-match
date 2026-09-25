package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {
}
