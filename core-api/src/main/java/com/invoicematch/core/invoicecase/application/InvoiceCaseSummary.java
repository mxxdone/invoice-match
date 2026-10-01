package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseListRow;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of one invoice case for the work list. It deliberately
 * excludes draft lines, evidence, match and review children so a page of the
 * list is one query with no N+1 and no entity graph load. {@code version} is the
 * optimistic case version the UI must echo back on a write.
 */
public record InvoiceCaseSummary(
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

    public static InvoiceCaseSummary from(InvoiceCaseListRow row) {
        return new InvoiceCaseSummary(
                row.id(),
                row.supplierId(),
                row.purchaseOrderId(),
                row.invoiceNumber(),
                row.submittedBy(),
                row.status(),
                row.version(),
                row.createdAt(),
                row.updatedAt(),
                row.submittedAt());
    }
}
