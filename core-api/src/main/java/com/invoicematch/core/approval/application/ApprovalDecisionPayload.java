package com.invoicematch.core.approval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure builder of the canonical approval decision payload stored on the
 * immutable {@code ReviewDecision}. It is a stateless helper so the approval
 * service does not grow another injected dependency, and it keeps its own
 * default {@link ObjectMapper} so the persisted bytes cannot shift if the Spring
 * global mapper is reconfigured.
 *
 * <p>Field names, order and numeric widths are part of the stored contract and
 * must not change.
 */
public final class ApprovalDecisionPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApprovalDecisionPayload() {
    }

    public static String canonicalJson(ApprovedAllocationPlan plan, long plannedQuantityTotal, String currency) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("amount", plan.totalAmount().amount());
        node.put("currency", currency);
        node.put("allocationCount", (long) plan.allocations().size());
        node.put("allocatedQuantity", plannedQuantityTotal);
        ArrayNode allocations = node.putArray("allocations");
        for (PlannedReceiptAllocation allocation : plan.allocations()) {
            ObjectNode item = allocations.addObject();
            item.put("invoiceLineNumber", allocation.invoiceLineNumber());
            item.put("receiptId", allocation.receiptId());
            item.put("receiptLineId", allocation.receiptLineId());
            item.put("quantity", allocation.plannedQuantity());
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Approval decision payload serialization failed", e);
        }
    }
}
