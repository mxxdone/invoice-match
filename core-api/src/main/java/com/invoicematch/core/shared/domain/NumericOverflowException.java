package com.invoicematch.core.shared.domain;

/**
 * Thrown when integer arithmetic on money or quantities would overflow the
 * underlying numeric type. Totals must never wrap silently.
 */
public class NumericOverflowException extends RuntimeException {

    public NumericOverflowException(String message) {
        super(message);
    }
}
