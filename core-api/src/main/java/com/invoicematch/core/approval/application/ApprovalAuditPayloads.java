package com.invoicematch.core.approval.application;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure before/after/allocation summary builder for the approval audit row.
 *
 * <p>The approval service renders the audit diff from the exact same
 * {@link ApprovalResult} it returns, so the audited values and the API response
 * cannot drift. Object key insertion order is not a stored contract:
 * {@code AuditStateSummarizer} recursively sorts object keys and the value is
 * stored as {@code jsonb}. Only the <em>array</em> order (the allocation
 * summaries) is meaningful and is preserved.
 */
public final class ApprovalAuditPayloads {

    private ApprovalAuditPayloads() {
    }

    public static Map<String, Object> before(long caseVersionBefore, ReviewSnapshot snapshot) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", InvoiceCaseStatus.REVIEW_PENDING.name());
        before.put("caseVersion", caseVersionBefore);
        before.put("reviewSnapshotId", snapshot.id().toString());
        before.put("reviewPayloadHash", snapshot.payloadHash());
        return before;
    }

    public static Map<String, Object> after(ApprovalResult result, long allocatedQuantityTotal) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", result.status());
        after.put("caseVersion", result.caseVersion());
        after.put("decisionId", result.reviewDecisionId().toString());
        after.put("decisionNumber", result.decisionNumber());
        after.put("reviewSnapshotId", result.reviewSnapshotId().toString());
        after.put("reviewPayloadHash", result.reviewPayloadHash());
        after.put("paymentRequestId", result.paymentRequestId().toString());
        after.put("externalRequestKey", result.externalRequestKey());
        after.put("amount", result.amount());
        after.put("currency", result.currency());
        after.put("allocationCount", (long) result.allocations().size());
        after.put("allocatedQuantity", allocatedQuantityTotal);
        after.put("allocations", result.allocations().stream()
                .map(ApprovalAuditPayloads::allocationSummary)
                .toList());
        return after;
    }

    private static Map<String, Object> allocationSummary(ApprovedAllocation allocation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("invoiceLineNumber", allocation.invoiceLineNumber());
        summary.put("receiptId", allocation.receiptId());
        summary.put("receiptLineId", allocation.receiptLineId());
        summary.put("quantity", allocation.allocatedQuantity());
        return summary;
    }
}
