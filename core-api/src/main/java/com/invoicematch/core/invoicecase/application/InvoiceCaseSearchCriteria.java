package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import java.time.Instant;

/**
 * Server-side work-list query. All fields are optional. Pagination is bounded by
 * {@link InvoiceCaseQueryService} so a caller can never request an unbounded
 * list, and the effective submitter scope is resolved from the authenticated
 * actor, not merely from {@link #submittedBy()}.
 *
 * <p>{@link #invoiceNumber()} and {@link #purchaseOrderId()} are partial
 * searches: the invoice number is normalized (uppercased, non-alphanumerics
 * removed) and matched as a literal substring of the stored normalized value,
 * while the purchase order id is a case-insensitive literal substring. Neither
 * exposes SQL wildcards; literal {@code %}, {@code _} and {@code \} match
 * themselves. {@link #supplierId()} and {@link #submittedBy()} remain exact
 * matches, and a blank input means "no filter".
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
