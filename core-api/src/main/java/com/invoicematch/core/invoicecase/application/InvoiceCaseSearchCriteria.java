package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import java.time.Instant;

/**
 * Server-side work-list query. All fields are optional. Pagination is bounded by
 * {@link InvoiceCaseQueryService} so a caller can never request an unbounded
 * list, and the effective submitter scope is resolved from the authenticated
 * actor, not merely from {@link #submittedBy()}.
 */
public record InvoiceCaseSearchCriteria(
        InvoiceCaseStatus status,
        String supplierId,
        String purchaseOrderId,
        String invoiceNumber,
        String submittedBy,
        Instant submittedFrom,
        Instant submittedTo,
        int page,
        int size) {
}
