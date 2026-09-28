package com.invoicematch.core.invoicecase.application;

import java.util.List;

/**
 * One server-paged slice of the work list plus the page metadata the UI needs to
 * render pagination without counting client-side. {@code items} is bounded by
 * the page size.
 */
public record InvoiceCasePage(
        List<InvoiceCaseSummary> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext) {

    public InvoiceCasePage {
        items = List.copyOf(items);
    }
}
