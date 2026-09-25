package com.invoicematch.core.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuantityTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, Integer.MAX_VALUE})
    void acceptsPositiveIntegers(int value) {
        assertThat(Quantity.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveQuantities(int value) {
        assertThatThrownBy(() -> Quantity.of(value))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void addsQuantities() {
        assertThat(Quantity.of(2).plus(Quantity.of(3))).isEqualTo(Quantity.of(5));
    }

    @Test
    void rejectsQuantityOverflow() {
        assertThatThrownBy(() -> Quantity.of(Integer.MAX_VALUE).plus(Quantity.of(1)))
                .isInstanceOf(NumericOverflowException.class);
    }
}
