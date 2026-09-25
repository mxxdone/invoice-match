package com.invoicematch.core.shared.domain;

/**
 * A non-negative quantity confirmed by the external purchasing system for a
 * receipt line. Unlike an ordered {@link Quantity} it may legitimately be zero
 * when the external record confirms no delivered units.
 *
 * <p>This models the external confirmed quantity only. The quantity that this
 * system later consumes from a receipt through allocation is a separate local
 * concept and must not reuse this value.
 */
public final class ConfirmedQuantity implements Comparable<ConfirmedQuantity> {

    private final int value;

    private ConfirmedQuantity(int value) {
        if (value < 0) {
            throw new DomainValidationException("ConfirmedQuantity must not be negative: " + value);
        }
        this.value = value;
    }

    public static ConfirmedQuantity of(int value) {
        return new ConfirmedQuantity(value);
    }

    public static ConfirmedQuantity zero() {
        return new ConfirmedQuantity(0);
    }

    public int value() {
        return value;
    }

    public boolean isZero() {
        return value == 0;
    }

    @Override
    public int compareTo(ConfirmedQuantity other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfirmedQuantity quantity && value == quantity.value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "ConfirmedQuantity[" + value + "]";
    }
}
