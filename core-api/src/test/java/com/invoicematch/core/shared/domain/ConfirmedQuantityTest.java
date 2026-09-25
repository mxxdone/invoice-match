package com.invoicematch.core.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfirmedQuantityTest {

    @Test
    void allowsZeroConfirmedQuantity() {
        ConfirmedQuantity zero = ConfirmedQuantity.zero();

        assertThat(zero.value()).isZero();
        assertThat(zero.isZero()).isTrue();
        assertThat(zero).isEqualTo(ConfirmedQuantity.of(0));
    }

    @Test
    void rejectsNegativeConfirmedQuantity() {
        assertThatThrownBy(() -> ConfirmedQuantity.of(-1))
                .isInstanceOf(DomainValidationException.class);
    }
}
