package com.invoicematch.core.invoicecase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.NumericOverflowException;
import com.invoicematch.core.shared.domain.Quantity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceLineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void computesLineTotalFromUnitPriceAndQuantity() {
        InvoiceLine line = InvoiceLine.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "A4 Paper", Quantity.of(3), Money.of(2500),
                null, T0);

        assertThat(line.lineTotal()).isEqualTo(Money.of(7500));
    }

    @Test
    void rejectsLineTotalOverflow() {
        InvoiceLine line = InvoiceLine.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Overflow", Quantity.of(2),
                Money.of(Long.MAX_VALUE), null, T0);

        assertThatThrownBy(line::lineTotal).isInstanceOf(NumericOverflowException.class);
    }
}
