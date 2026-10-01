package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import java.time.Instant;
import java.util.List;

/**
 * Persistence-owned input of one invoice case work-list query. The application
 * resolves the authenticated actor scope, the normalized invoice number and the
 * effective status set before building this, so access-scope policy never lives
 * in SQL construction.
 *
 * <p>{@code page} is a {@code long}: the caller may legitimately send the
 * largest supported page and the offset is validated while it is still a
 * {@code long}, before it is narrowed for the JDBC result window.
 */
public record InvoiceCaseListQuery(
        List<InvoiceCaseStatus> statuses,
        String submitterScope,
        String supplierId,
        String purchaseOrderId,
        String normalizedInvoiceNumber,
        Instant submittedFrom,
        Instant submittedTo,
        long page,
        int size) {

    public InvoiceCaseListQuery {
        statuses = List.copyOf(statuses);
    }
}
