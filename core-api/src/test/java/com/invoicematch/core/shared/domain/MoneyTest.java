package com.invoicematch.core.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void acceptsZeroAndPositiveWon() {
        assertThat(Money.of(0).amount()).isZero();
        assertThat(Money.of(1).amount()).isEqualTo(1);
        assertThat(Money.zero().isZero()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -1000, Long.MIN_VALUE})
    void rejectsNegativeAmounts(long amount) {
        assertThatThrownBy(() -> Money.of(amount))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void addsAmounts() {
        assertThat(Money.of(1200).plus(Money.of(800))).isEqualTo(Money.of(2000));
    }

    @Test
    void rejectsTotalOverflowOnAdd() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).plus(Money.of(1)))
                .isInstanceOf(NumericOverflowException.class);
    }

    @Test
    void multipliesUnitPriceByQuantity() {
        assertThat(Money.of(2500).multiply(Quantity.of(3))).isEqualTo(Money.of(7500));
        assertThat(Money.of(0).multiply(Quantity.of(Integer.MAX_VALUE))).isEqualTo(Money.zero());
    }

    @Test
    void rejectsTotalOverflowOnMultiply() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).multiply(Quantity.of(2)))
                .isInstanceOf(NumericOverflowException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE / 2 + 1).multiply(Quantity.of(2)))
                .isInstanceOf(NumericOverflowException.class);
    }

    @Test
    void comparesByValue() {
        assertThat(Money.of(1000)).isGreaterThan(Money.of(999));
        assertThat(Money.of(1000)).isEqualByComparingTo(Money.of(1000));
    }
}
