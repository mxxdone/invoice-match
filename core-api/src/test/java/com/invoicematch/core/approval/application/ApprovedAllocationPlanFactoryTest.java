package com.invoicematch.core.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.approval.domain.ApprovalNotPermittedException;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.matching.application.MatchComputation;
import com.invoicematch.core.matching.domain.MatchLineOutcome;
import com.invoicematch.core.matching.domain.MatchLineStatus;
import com.invoicematch.core.matching.domain.MatchPoLine;
import com.invoicematch.core.matching.domain.PlannedAllocation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the typed allocation plan factory: it derives the plan and
 * amount from a verified typed match computation with checked arithmetic, and
 * rejects abnormal, incomplete or overflowing plans without any JSON coercion.
 */
class ApprovedAllocationPlanFactoryTest {

    private final ApprovedAllocationPlanFactory factory = new ApprovedAllocationPlanFactory();
    private final UUID caseId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();

    @Test
    void derivesTypedPlanAndTotal() {
        EvidenceBundlePayload.EvidenceLine line = new EvidenceBundlePayload.EvidenceLine(1, "A4", 10, 2500, "ITEM");
        MatchComputation computation = new MatchComputation("{}", "hash", true, List.of(
                matchedOutcome(1, 10, 10, allocation("R1", "L1", 10))));

        ApprovedAllocationPlan plan =
                factory.from(caseId, snapshotId, computation, List.of(line));

        assertThat(plan.totalAmount().amount()).isEqualTo(25_000L);
        assertThat(plan.allocations()).hasSize(1);
        PlannedReceiptAllocation allocation = plan.allocations().get(0);
        assertThat(allocation.invoiceLineNumber()).isEqualTo(1);
        assertThat(allocation.purchaseOrderLineId()).isEqualTo("POL-1");
        assertThat(allocation.receiptLineId()).isEqualTo("L1");
        assertThat(allocation.plannedQuantity()).isEqualTo(10);
    }

    @Test
    void rejectsAbnormalMatch() {
        EvidenceBundlePayload.EvidenceLine line = new EvidenceBundlePayload.EvidenceLine(1, "A4", 10, 2500, "ITEM");
        MatchComputation computation = new MatchComputation("{}", "hash", false, List.of(
                matchedOutcome(1, 10, 10, allocation("R1", "L1", 10))));

        assertThatThrownBy(() -> factory.from(caseId, snapshotId, computation, List.of(line)))
                .isInstanceOf(ApprovalNotPermittedException.class)
                .hasMessageContaining("not normal");
    }

    @Test
    void rejectsIncompletePlan() {
        EvidenceBundlePayload.EvidenceLine line = new EvidenceBundlePayload.EvidenceLine(1, "A4", 10, 2500, "ITEM");
        MatchComputation computation = new MatchComputation("{}", "hash", true, List.of(
                matchedOutcome(1, 10, 4, allocation("R1", "L1", 4))));

        assertThatThrownBy(() -> factory.from(caseId, snapshotId, computation, List.of(line)))
                .isInstanceOf(ApprovalNotPermittedException.class)
                .hasMessageContaining("allocation plan");
    }

    @Test
    void rejectsOverflowingTotal() {
        EvidenceBundlePayload.EvidenceLine first =
                new EvidenceBundlePayload.EvidenceLine(1, "A4", 1, Long.MAX_VALUE, "ITEM");
        EvidenceBundlePayload.EvidenceLine second =
                new EvidenceBundlePayload.EvidenceLine(2, "A4", 1, Long.MAX_VALUE, "ITEM");
        MatchComputation computation = new MatchComputation("{}", "hash", true, List.of(
                matchedOutcome(1, 1, 1, allocation("R1", "L1", 1)),
                matchedOutcome(2, 1, 1, allocation("R1", "L2", 1))));

        assertThatThrownBy(() -> factory.from(caseId, snapshotId, computation, List.of(first, second)))
                .isInstanceOf(ApprovalNotPermittedException.class)
                .hasMessageContaining("overflow");
    }

    private MatchLineOutcome matchedOutcome(int lineNumber, int invoiceQuantity, long plannedQuantity,
            PlannedAllocation... allocations) {
        MatchPoLine poLine = new MatchPoLine("POL-1", "ITEM", 100, 2500);
        return new MatchLineOutcome(
                lineNumber,
                "A4",
                "ITEM",
                MatchLineStatus.MATCHED,
                List.of("POL-1"),
                poLine,
                invoiceQuantity,
                2500,
                plannedQuantity,
                List.of(allocations),
                plannedQuantity,
                List.of());
    }

    private PlannedAllocation allocation(String receiptId, String receiptLineId, int quantity) {
        return new PlannedAllocation(receiptId, receiptLineId, LocalDate.of(2026, 1, 5), 1, 100, quantity);
    }
}
