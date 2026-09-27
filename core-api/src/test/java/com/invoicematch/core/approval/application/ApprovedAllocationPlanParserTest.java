package com.invoicematch.core.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.approval.domain.ApprovalNotPermittedException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the frozen-plan parser: only a normal match whose plan covers
 * every line and whose line amounts equal the frozen total is approvable.
 */
class ApprovedAllocationPlanParserTest {

    private final ApprovedAllocationPlanParser parser = new ApprovedAllocationPlanParser();
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID caseId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();

    @Test
    void parsesFifoPlanAndTotal() {
        ObjectNode payload = base(true);
        addInvoiceLine(payload, 1, 40, 2500);
        addInvoiceLine(payload, 2, 20, 2500);
        payload.put("totalAmount", 150_000L);
        ObjectNode match = matchPayload(payload);
        addOutcome(match, 1, 40, 40, allocation("RCV-1", "RCL-1", "2026-01-05", 30),
                allocation("RCV-1", "RCL-2", "2026-01-06", 10));
        addOutcome(match, 2, 20, 20, allocation("RCV-1", "RCL-2", "2026-01-06", 20));

        ApprovedAllocationPlan plan = parser.parse(caseId, snapshotId, payload.toString());

        assertThat(plan.totalAmount().amount()).isEqualTo(150_000L);
        assertThat(plan.allocations()).hasSize(3);
        assertThat(plan.allocations().get(0).invoiceLineNumber()).isEqualTo(1);
        assertThat(plan.allocations().get(0).receiptLineId()).isEqualTo("RCL-1");
        assertThat(plan.allocations().get(0).receiptDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(plan.allocations().get(1).plannedQuantity()).isEqualTo(10);
        assertThat(plan.allocations().get(2).invoiceLineNumber()).isEqualTo(2);
    }

    @Test
    void rejectsAbnormalMatch() {
        ObjectNode payload = base(false);
        addInvoiceLine(payload, 1, 40, 2500);
        payload.put("totalAmount", 100_000L);
        ObjectNode match = matchPayload(payload);
        addOutcome(match, 1, 40, 40, allocation("RCV-1", "RCL-1", "2026-01-05", 40));

        assertThatThrownBy(() -> parser.parse(caseId, snapshotId, payload.toString()))
                .isInstanceOf(ApprovalNotPermittedException.class)
                .hasMessageContaining("not normal");
    }

    @Test
    void rejectsMissingPlan() {
        ObjectNode payload = base(true);
        addInvoiceLine(payload, 1, 40, 2500);
        payload.put("totalAmount", 100_000L);
        ObjectNode match = matchPayload(payload);
        addOutcome(match, 1, 40, 40);

        assertThatThrownBy(() -> parser.parse(caseId, snapshotId, payload.toString()))
                .isInstanceOf(ApprovalNotPermittedException.class)
                .hasMessageContaining("allocation plan");
    }

    @Test
    void rejectsMismatchedTotal() {
        ObjectNode payload = base(true);
        addInvoiceLine(payload, 1, 40, 2500);
        payload.put("totalAmount", 99_999L);
        ObjectNode match = matchPayload(payload);
        addOutcome(match, 1, 40, 40, allocation("RCV-1", "RCL-1", "2026-01-05", 40));

        assertThatThrownBy(() -> parser.parse(caseId, snapshotId, payload.toString()))
                .isInstanceOf(ApprovalNotPermittedException.class)
                .hasMessageContaining("frozen total");
    }

    @Test
    void rejectsOverflowingTotal() {
        ObjectNode payload = base(true);
        addInvoiceLine(payload, 1, 1, Long.MAX_VALUE);
        addInvoiceLine(payload, 2, 1, Long.MAX_VALUE);
        payload.put("totalAmount", Long.MAX_VALUE);
        ObjectNode match = matchPayload(payload);
        addOutcome(match, 1, 1, 1, allocation("RCV-1", "RCL-1", "2026-01-05", 1));
        addOutcome(match, 2, 1, 1, allocation("RCV-1", "RCL-2", "2026-01-06", 1));

        assertThatThrownBy(() -> parser.parse(caseId, snapshotId, payload.toString()))
                .isInstanceOf(ApprovalNotPermittedException.class)
                .hasMessageContaining("overflow");
    }

    private ObjectNode base(boolean normal) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", "review-snapshot-v1");
        root.put("caseId", caseId.toString());
        root.put("caseVersion", 3);
        root.putArray("invoiceLines");
        root.putObject("matchResult").putObject("payload").put("normal", normal);
        return root;
    }

    private void addInvoiceLine(ObjectNode payload, int lineNumber, int quantity, long unitPrice) {
        ArrayNode lines = (ArrayNode) payload.get("invoiceLines");
        ObjectNode line = lines.addObject();
        line.put("lineNumber", lineNumber);
        line.put("quantity", quantity);
        line.put("unitPrice", unitPrice);
        line.put("lineAmount", quantity * unitPrice);
    }

    private ObjectNode matchPayload(ObjectNode payload) {
        return (ObjectNode) payload.get("matchResult").get("payload");
    }

    private void addOutcome(ObjectNode match, int lineNumber, int invoiceQuantity, int plannedQuantity,
            ObjectNode... allocations) {
        ArrayNode outcomes = match.withArray("lineOutcomes");
        ObjectNode outcome = outcomes.addObject();
        outcome.put("lineNumber", lineNumber);
        outcome.put("status", plannedQuantity >= invoiceQuantity ? "MATCHED" : "EVIDENCE_INSUFFICIENT");
        outcome.put("invoiceQuantity", invoiceQuantity);
        outcome.put("plannedQuantity", plannedQuantity);
        ArrayNode plan = outcome.putArray("expectedAllocationPlan");
        for (ObjectNode allocation : allocations) {
            plan.add(allocation);
        }
    }

    private ObjectNode allocation(String receiptId, String receiptLineId, String date, int quantity) {
        ObjectNode node = mapper.createObjectNode();
        node.put("receiptId", receiptId);
        node.put("receiptLineId", receiptLineId);
        node.put("receiptDate", date);
        node.put("plannedQuantity", quantity);
        return node;
    }
}
