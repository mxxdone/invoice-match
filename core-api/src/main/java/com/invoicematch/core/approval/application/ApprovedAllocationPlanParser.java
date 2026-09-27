package com.invoicematch.core.approval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.approval.domain.ApprovalNotPermittedException;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.NumericOverflowException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads the frozen expected allocation plan out of a canonical review snapshot
 * payload.
 *
 * <p>Only a {@code normal} match whose every line outcome is {@code MATCHED},
 * whose expected plan covers the full invoice quantity, and whose line amounts
 * add up to the frozen total (checked arithmetic) is approvable. Anything else —
 * unresolved/abnormal outcomes, a missing or short plan, an inconsistent total —
 * is rejected with {@link ApprovalNotPermittedException} and no side effect.
 * Client-supplied amounts are never consulted.
 */
@Component
public class ApprovedAllocationPlanParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovedAllocationPlan parse(UUID caseId, UUID reviewSnapshotId, String snapshotPayloadJson) {
        JsonNode root = read(snapshotPayloadJson);
        List<String> reasons = new ArrayList<>();

        JsonNode matchPayload = root.path("matchResult").path("payload");
        if (matchPayload.isMissingNode()) {
            throw new ApprovalNotPermittedException(
                    caseId, reviewSnapshotId, List.of("the snapshot does not carry a match result payload"));
        }
        if (!matchPayload.path("normal").asBoolean(false)) {
            reasons.add("the match result is not normal");
        }

        JsonNode outcomes = matchPayload.path("lineOutcomes");
        Map<Integer, JsonNode> outcomeByLine = new LinkedHashMap<>();
        for (JsonNode outcome : outcomes) {
            outcomeByLine.put(outcome.path("lineNumber").asInt(), outcome);
        }

        JsonNode invoiceLines = root.path("invoiceLines");
        if (!invoiceLines.isArray() || invoiceLines.isEmpty()) {
            reasons.add("the snapshot carries no invoice lines");
        }

        Money computedTotal = Money.zero();
        List<PlannedReceiptAllocation> allocations = new ArrayList<>();
        for (JsonNode line : invoiceLines) {
            int lineNumber = line.path("lineNumber").asInt();
            int quantity = line.path("quantity").asInt();
            long lineAmount = line.path("lineAmount").asLong();
            try {
                computedTotal = computedTotal.plus(Money.of(lineAmount));
            } catch (NumericOverflowException overflow) {
                throw new ApprovalNotPermittedException(
                        caseId, reviewSnapshotId, List.of("approved line amounts overflow: " + overflow.getMessage()));
            }

            JsonNode outcome = outcomeByLine.get(lineNumber);
            if (outcome == null) {
                reasons.add("missing match outcome for invoice line " + lineNumber);
                continue;
            }
            String status = outcome.path("status").asText();
            if (!"MATCHED".equals(status)) {
                reasons.add("invoice line " + lineNumber + " is unresolved (" + status + ")");
                continue;
            }
            int plannedQuantity = outcome.path("plannedQuantity").asInt();
            if (plannedQuantity != quantity) {
                reasons.add("invoice line " + lineNumber + " has an incomplete allocation plan");
            }

            JsonNode plan = outcome.path("expectedAllocationPlan");
            int planTotal = 0;
            if (!plan.isArray() || plan.isEmpty()) {
                reasons.add("invoice line " + lineNumber + " has no expected allocation plan");
                continue;
            }
            for (JsonNode allocation : plan) {
                int planned = allocation.path("plannedQuantity").asInt();
                planTotal += planned;
                String receiptDate = allocation.path("receiptDate").asText();
                if (planned <= 0 || receiptDate.isBlank()) {
                    reasons.add("invoice line " + lineNumber + " has a malformed allocation entry");
                    continue;
                }
                try {
                    allocations.add(new PlannedReceiptAllocation(
                            lineNumber,
                            allocation.path("receiptId").asText(),
                            allocation.path("receiptLineId").asText(),
                            LocalDate.parse(receiptDate),
                            planned));
                } catch (DateTimeParseException e) {
                    reasons.add("invoice line " + lineNumber + " has a malformed receipt date");
                }
            }
            if (planTotal != quantity) {
                reasons.add("invoice line " + lineNumber + " allocation plan does not cover its quantity");
            }
        }

        long frozenTotal = root.path("totalAmount").asLong();
        if (computedTotal.amount() != frozenTotal) {
            reasons.add("approved line amounts do not equal the frozen total amount");
        }

        if (!reasons.isEmpty()) {
            throw new ApprovalNotPermittedException(caseId, reviewSnapshotId, reasons.stream().distinct().toList());
        }
        return new ApprovedAllocationPlan(allocations, Money.of(frozenTotal));
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored review snapshot payload is not readable", e);
        }
    }
}
