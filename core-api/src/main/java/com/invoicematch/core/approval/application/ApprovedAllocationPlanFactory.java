package com.invoicematch.core.approval.application;

import com.invoicematch.core.approval.domain.ApprovalNotPermittedException;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.matching.domain.MatchLineOutcome;
import com.invoicematch.core.matching.domain.MatchLineStatus;
import com.invoicematch.core.matching.domain.PlannedAllocation;
import com.invoicematch.core.matching.application.MatchComputation;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.NumericOverflowException;
import com.invoicematch.core.shared.domain.Quantity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the typed approval allocation plan from an independently recomputed
 * deterministic match.
 *
 * <p>It consumes only typed values ({@link MatchComputation} and the
 * authoritative invoice lines reconstructed from the sealed draft), so there is
 * no {@code asInt}/{@code asLong} coercion and no unchecked addition. Amounts
 * use checked {@link Money}/{@link Quantity} arithmetic. A plan is approvable
 * only when the match is {@code normal}, every line is {@code MATCHED} with a
 * complete plan, every planned receipt consumption is positive and the approved
 * total is positive.
 */
@Component
public class ApprovedAllocationPlanFactory {

    public ApprovedAllocationPlan from(
            UUID caseId,
            UUID reviewSnapshotId,
            MatchComputation computation,
            List<EvidenceBundlePayload.EvidenceLine> invoiceLines) {
        List<String> reasons = new ArrayList<>();
        if (!computation.normal()) {
            reasons.add("the match result is not normal");
        }

        Map<Integer, EvidenceBundlePayload.EvidenceLine> lineByNumber = new LinkedHashMap<>();
        for (EvidenceBundlePayload.EvidenceLine line : invoiceLines) {
            lineByNumber.put(line.lineNumber(), line);
        }

        Money total = Money.zero();
        for (EvidenceBundlePayload.EvidenceLine line : invoiceLines) {
            try {
                total = total.plus(Money.of(line.unitPrice()).multiply(Quantity.of(line.quantity())));
            } catch (NumericOverflowException overflow) {
                throw new ApprovalNotPermittedException(
                        caseId,
                        reviewSnapshotId,
                        List.of("approved line amounts overflow: " + overflow.getMessage()));
            }
        }

        List<PlannedReceiptAllocation> allocations = new ArrayList<>();
        for (MatchLineOutcome outcome : computation.lineOutcomes()) {
            EvidenceBundlePayload.EvidenceLine line = lineByNumber.get(outcome.lineNumber());
            if (line == null) {
                reasons.add("match outcome references an unknown invoice line " + outcome.lineNumber());
                continue;
            }
            if (outcome.status() != MatchLineStatus.MATCHED) {
                reasons.add("invoice line " + outcome.lineNumber() + " is unresolved (" + outcome.status() + ")");
                continue;
            }
            if (outcome.purchaseOrderLine() == null) {
                reasons.add("invoice line " + outcome.lineNumber() + " has no matched purchase order line");
                continue;
            }
            if (!outcome.hasCompleteExpectedPlan()) {
                reasons.add("invoice line " + outcome.lineNumber() + " has an incomplete allocation plan");
            }

            long planSum = 0L;
            for (PlannedAllocation allocation : outcome.expectedAllocationPlan()) {
                try {
                    planSum = Math.addExact(planSum, allocation.plannedQuantity());
                } catch (ArithmeticException overflow) {
                    throw new ApprovalNotPermittedException(
                            caseId,
                            reviewSnapshotId,
                            List.of("planned quantity overflow on invoice line " + outcome.lineNumber()));
                }
                allocations.add(new PlannedReceiptAllocation(
                        outcome.lineNumber(),
                        outcome.purchaseOrderLine().purchaseOrderLineId(),
                        allocation.receiptId(),
                        allocation.receiptLineId(),
                        allocation.receiptDate(),
                        allocation.receiptLineVersion(),
                        allocation.confirmedQuantity(),
                        allocation.plannedQuantity()));
            }
            if (planSum != outcome.invoiceQuantity()) {
                reasons.add("invoice line " + outcome.lineNumber()
                        + " allocation plan does not cover its quantity");
            }
        }

        if (computation.lineOutcomes().size() != invoiceLines.size()) {
            reasons.add("the match result does not cover every invoice line");
        }

        if (total.isZero()) {
            reasons.add("the approved amount must be positive");
        }

        if (!reasons.isEmpty()) {
            throw new ApprovalNotPermittedException(caseId, reviewSnapshotId, reasons.stream().distinct().toList());
        }
        return new ApprovedAllocationPlan(allocations, total);
    }
}
