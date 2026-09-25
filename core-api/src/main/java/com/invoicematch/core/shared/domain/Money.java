package com.invoicematch.core.shared.domain;

import java.util.Objects;

/**
 * A non-negative KRW amount expressed as whole won. VAT, shipping, discounts
 * and fractional amounts are out of scope for the V1 baseline.
 */
public final class Money implements Comparable<Money> {

    private final long amount;

    private Money(long amount) {
        if (amount < 0) {
            throw new DomainValidationException("KRW amount must not be negative: " + amount);
        }
        this.amount = amount;
    }

    public static Money of(long amount) {
        return new Money(amount);
    }

    public static Money zero() {
        return new Money(0L);
    }

    public long amount() {
        return amount;
    }

    public boolean isZero() {
        return amount == 0L;
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        try {
            return new Money(Math.addExact(amount, other.amount));
        } catch (ArithmeticException overflow) {
            throw new NumericOverflowException(
                    "KRW total overflow adding " + amount + " and " + other.amount);
        }
    }

    /**
     * Multiplies this unit amount by a positive quantity, guarding against
     * {@code long} overflow.
     */
    public Money multiply(Quantity quantity) {
        Objects.requireNonNull(quantity, "quantity");
        try {
            return new Money(Math.multiplyExact(amount, (long) quantity.value()));
        } catch (ArithmeticException overflow) {
            throw new NumericOverflowException(
                    "KRW total overflow multiplying " + amount + " by " + quantity.value());
        }
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(amount, other.amount);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Money money && amount == money.amount;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(amount);
    }

    @Override
    public String toString() {
        return "Money[" + amount + " KRW]";
    }
}
