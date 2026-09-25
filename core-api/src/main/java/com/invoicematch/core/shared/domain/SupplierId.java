package com.invoicematch.core.shared.domain;

import java.util.Objects;

/**
 * External identifier of the supplier that issued the invoice. The purchasing
 * system of record owns the supplier master; Phase 1 only references it.
 */
public record SupplierId(String value) {

    public SupplierId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new DomainValidationException("SupplierId must not be blank");
        }
    }

    public static SupplierId of(String value) {
        return new SupplierId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
