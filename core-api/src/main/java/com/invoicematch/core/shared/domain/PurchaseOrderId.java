package com.invoicematch.core.shared.domain;

import java.util.Objects;

/**
 * External identifier of the purchase order that an invoice case references.
 * The purchasing system of record owns the purchase order master.
 */
public record PurchaseOrderId(String value) {

    public PurchaseOrderId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new DomainValidationException("PurchaseOrderId must not be blank");
        }
    }

    public static PurchaseOrderId of(String value) {
        return new PurchaseOrderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
