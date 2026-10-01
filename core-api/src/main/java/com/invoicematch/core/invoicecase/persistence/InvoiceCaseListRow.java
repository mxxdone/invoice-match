package com.invoicematch.core.invoicecase.persistence;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Typed projection of one invoice case work-list row, owned by persistence so
 * the query projection never depends on an application DTO. It deliberately
 * excludes draft lines, evidence, match and review children so a page is one
 * query with no N+1; the application maps this row to its response DTO.
 */
public record InvoiceCaseListRow(
        UUID id,
        String supplierId,
        String purchaseOrderId,
        String invoiceNumber,
        String submittedBy,
        InvoiceCaseStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt) {
}
