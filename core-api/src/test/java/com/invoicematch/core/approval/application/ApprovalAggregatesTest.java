package com.invoicematch.core.approval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.approval.domain.ReceiptAllocation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Checked aggregate arithmetic: totals beyond {@code Integer.MAX_VALUE} are
 * represented exactly as {@code long}, never wrapped to an {@code int}, and an
 * impossible {@code long} overflow fails instead of silently wrapping.
 */
class ApprovalAggregatesTest {

    @Test
    void plannedQuantityTotalBeyondIntegerMaxIsExactLong() {
        long total = ApprovalAggregates.sumPlannedQuantity(List.of(
                planned(Integer.MAX_VALUE), planned(Integer.MAX_VALUE), planned(7)));

        assertThat(total).isEqualTo(2L * Integer.MAX_VALUE + 7);
    }

    @Test
    void allocatedQuantityTotalBeyondIntegerMaxIsExactLong() {
        long total = ApprovalAggregates.sumAllocatedQuantity(List.of(
                allocated(Integer.MAX_VALUE), allocated(Integer.MAX_VALUE), allocated(7)));

        assertThat(total).isEqualTo(2L * Integer.MAX_VALUE + 7);
    }

    private PlannedReceiptAllocation planned(int quantity) {
        return new PlannedReceiptAllocation(
                1, "POL-1", "RCV-1", "RCL-1", LocalDate.of(2026, 1, 1), 1, Integer.MAX_VALUE, quantity);
    }

    private ReceiptAllocation allocated(int quantity) {
        return ReceiptAllocation.record(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PO-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "hash",
                1,
                UUID.randomUUID(),
                "RCV-1",
                "RCL-1",
                "POL-1",
                1L,
                Integer.MAX_VALUE,
                quantity,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
