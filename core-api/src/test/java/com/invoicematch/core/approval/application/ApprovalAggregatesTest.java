package com.invoicematch.core.approval.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checked aggregate arithmetic: totals beyond {@code Integer.MAX_VALUE} are
 * represented exactly as {@code long}, never wrapped to an {@code int}.
 */
class ApprovalAggregatesTest {

    @Test
    void plannedQuantityTotalBeyondIntegerMaxIsExactLong() {
        long total = ApprovalAggregates.sumPlannedQuantity(List.of(
                planned(Integer.MAX_VALUE), planned(Integer.MAX_VALUE), planned(7)));

        assertThat(total).isEqualTo(2L * Integer.MAX_VALUE + 7);
    }

    private PlannedReceiptAllocation planned(int quantity) {
        return new PlannedReceiptAllocation(
                1, "POL-1", "RCV-1", "RCL-1", LocalDate.of(2026, 1, 1), 1, Integer.MAX_VALUE, quantity);
    }
}
