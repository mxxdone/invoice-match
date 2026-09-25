package com.invoicematch.core.invoicecase.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of an {@link InvoiceCase}. A UUID is generated locally; no
 * distributed ID service is introduced for Phase 1.
 */
public record InvoiceCaseId(UUID value) {

    public InvoiceCaseId {
        Objects.requireNonNull(value, "value");
    }

    public static InvoiceCaseId newId() {
        return new InvoiceCaseId(UUID.randomUUID());
    }

    public static InvoiceCaseId of(UUID value) {
        return new InvoiceCaseId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
