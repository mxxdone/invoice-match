package com.invoicematch.core.shared.domain;

import java.util.Objects;

/**
 * A positive integer quantity of goods. Fractional quantities are out of scope
 * for the V1 baseline.
 */
public final class Quantity implements Comparable<Quantity> {

    private final int value;

    private Quantity(int value) {
        if (value <= 0) {
            throw new DomainValidationException("Quantity must be positive: " + value);
        }
        this.value = value;
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }

    public int value() {
        return value;
    }

    public Quantity plus(Quantity other) {
        Objects.requireNonNull(other, "other");
        try {
            return new Quantity(Math.addExact(value, other.value));
        } catch (ArithmeticException overflow) {
            throw new NumericOverflowException(
                    "Quantity overflow adding " + value + " and " + other.value);
        }
    }

    @Override
    public int compareTo(Quantity other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Quantity quantity && value == quantity.value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "Quantity[" + value + "]";
    }
}
