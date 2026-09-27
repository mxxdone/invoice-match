package com.invoicematch.core.approval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.approval.domain.ApprovalNotPermittedException;
import com.invoicematch.core.approval.domain.InsufficientReceiptBalanceException;
import com.invoicematch.core.approval.domain.PaymentRequest;
import com.invoicematch.core.approval.domain.ReceiptAllocation;
import com.invoicematch.core.approval.domain.ReceiptBalanceShortfall;
import com.invoicematch.core.approval.persistence.PaymentRequestRepository;
import com.invoicematch.core.approval.persistence.ReceiptAllocationRepository;
import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.domain.StaleCaseVersionException;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.matching.persistence.MatchResultRepository;
import com.invoicematch.core.purchasingreference.application.PreparedPurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotLock;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.persistence.PurchaseOrderSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshotRepository;
import com.invoicematch.core.review.application.ReviewCurrentnessService;
import com.invoicematch.core.review.domain.ReviewDecision;
import com.invoicematch.core.review.domain.ReviewDecisionType;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import com.invoicematch.core.review.persistence.ReviewDecisionRepository;
import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional approval write.
 *
 * <p>Lock order (Spec 13.1, compatible with P1-05/P1-06):
 * <ol>
 *   <li>lock the authoritative invoice case;</li>
 *   <li>enforce APPROVER and actor != immutable {@code submittedBy};</li>
 *   <li>reserve the actor-scoped request id;</li>
 *   <li>acquire the shared purchasing advisory lock;</li>
 *   <li>atomically apply the externally prepared purchasing snapshot (the
 *       external HTTP call already happened before this transaction);</li>
 *   <li>revalidate case state/version, exact snapshot id/hash, evidence bundle,
 *       match result, mapping watermark and purchasing currentness;</li>
 *   <li>derive the allocation intent from the frozen snapshot only;</li>
 *   <li>lock every referenced active receipt line in deterministic order;</li>
 *   <li>recompute remaining = confirmed - committed allocations;</li>
 *   <li>write all allocations, the APPROVED decision, one PaymentRequest, the
 *       APPROVE audit and transition the case to EXPORT_PENDING — all or
 *       nothing.</li>
 * </ol>
 *
 * <p>No HTTP or external call is made while a transaction, row or advisory lock
 * is held.
 */
@Service
public class ApprovalService {

    public static final String SCOPE_APPROVE = "invoice-case:approve";
    private static final String CURRENCY = "KRW";

    private final InvoiceCaseRepository invoiceCases;
    private final ReviewDecisionRepository decisions;
    private final MatchResultRepository matchResults;
    private final ReviewCurrentnessService currentness;
    private final ApprovedAllocationPlanParser planParser;
    private final PurchaseOrderSnapshotRepository purchaseOrderSnapshots;
    private final PurchaseOrderSnapshotLock purchaseOrderLock;
    private final PurchasingReferenceService purchasingReferenceService;
    private final ReceiptLineSnapshotRepository receiptLines;
    private final ReceiptAllocationRepository receiptAllocations;
    private final PaymentRequestRepository paymentRequests;
    private final RequestIdempotencyStore idempotency;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final ApprovalInterceptor interceptor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    public ApprovalService(
            InvoiceCaseRepository invoiceCases,
            ReviewDecisionRepository decisions,
            MatchResultRepository matchResults,
            ReviewCurrentnessService currentness,
            ApprovedAllocationPlanParser planParser,
            PurchaseOrderSnapshotRepository purchaseOrderSnapshots,
            PurchaseOrderSnapshotLock purchaseOrderLock,
            PurchasingReferenceService purchasingReferenceService,
            ReceiptLineSnapshotRepository receiptLines,
            ReceiptAllocationRepository receiptAllocations,
            PaymentRequestRepository paymentRequests,
            RequestIdempotencyStore idempotency,
            AuthorizationService authorization,
            AuditRecorder audit,
            ObjectProvider<ApprovalInterceptor> approvalInterceptors,
            Clock clock) {
        this.invoiceCases = invoiceCases;
        this.decisions = decisions;
        this.matchResults = matchResults;
        this.currentness = currentness;
        this.planParser = planParser;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.purchaseOrderLock = purchaseOrderLock;
        this.purchasingReferenceService = purchasingReferenceService;
        this.receiptLines = receiptLines;
        this.receiptAllocations = receiptAllocations;
        this.paymentRequests = paymentRequests;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.audit = audit;
        this.interceptor = approvalInterceptors.getIfAvailable(() -> ApprovalInterceptor.NONE);
        this.clock = clock;
    }

    @Transactional
    public CommandResult<ApprovalResult> approve(
            ApproveInvoiceCaseCommand command, String requestHash, PreparedPurchaseOrderSnapshot preparedSnapshot) {
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireApproverNotSubmitter(invoiceCase);
        Actor actor = authorization.actor();
        String purchaseOrderId = invoiceCase.purchaseOrder().value();

        String resourceKey = command.caseId().toString();
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_APPROVE, resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return new CommandResult<>(
                    replay.response().status(), idempotency.decode(replay.response(), ApprovalResult.class));
        }

        checkExpectedVersion(invoiceCase, command.expectedCaseVersion());

        purchaseOrderLock.acquireXactLock(purchaseOrderId);
        interceptor.afterPurchaseOrderLocked(command.caseId());

        // Apply the externally prepared snapshot atomically. The external call
        // happened before this transaction and no lock was held during it.
        purchasingReferenceService.applyPrepared(preparedSnapshot);

        // The authoritative current purchasing version/hash is exactly what this
        // transaction just applied. A REQUIRES_NEW read would not see the
        // uncommitted apply and could wrongly pass.
        PurchaseOrderSnapshot stored = purchaseOrderSnapshots
                .findById(purchaseOrderId)
                .orElseThrow(() -> new ReviewStateConflictException(
                        command.caseId(), "no current purchasing snapshot exists after refresh"));
        long currentPurchasingVersion = stored.snapshotVersion();
        String currentPurchasingHash = stored.payloadHash();

        ReviewSnapshot snapshot = currentness.loadSnapshot(command.caseId(), command.reviewSnapshotId());
        if (!snapshot.payloadHash().equals(command.reviewPayloadHash())) {
            throw new ReviewStateConflictException(
                    command.caseId(),
                    "review payload hash does not match the target snapshot " + command.reviewSnapshotId());
        }
        currentness.requireCurrent(
                invoiceCase, snapshot, currentPurchasingVersion, currentPurchasingHash);

        MatchResult matchResult = loadMatchResult(command.caseId(), snapshot);
        ApprovedAllocationPlan plan =
                planParser.parse(command.caseId(), snapshot.id(), snapshot.payload());
        requireSameCaseMatchResult(command.caseId(), matchResult);

        List<ResolvedAllocation> resolved = resolveAndLockReceiptLines(command.caseId(), snapshot, invoiceCase, plan);

        Instant now = clock.instant();

        int decisionNumber = decisions.maxDecisionNumber(command.caseId()) + 1;
        ReviewDecision decision = ReviewDecision.record(
                UUID.randomUUID(),
                command.caseId(),
                snapshot.id(),
                decisionNumber,
                ReviewDecisionType.APPROVED,
                actor.username(),
                null,
                decisionPayload(plan),
                snapshot.payloadHash(),
                now);
        decisions.saveAndFlush(decision);
        interceptor.afterDecisionWritten(command.caseId());

        List<ReceiptAllocation> allocations = resolved.stream()
                .map(allocation -> ReceiptAllocation.record(
                        UUID.randomUUID(),
                        command.caseId(),
                        purchaseOrderId,
                        decision.id(),
                        snapshot.id(),
                        snapshot.evidenceBundleId(),
                        allocation.invoiceLineNumber(),
                        allocation.row().id(),
                        allocation.row().receiptId(),
                        allocation.row().receiptLineId(),
                        allocation.plannedQuantity(),
                        now))
                .toList();
        receiptAllocations.saveAll(allocations);
        receiptAllocations.flush();
        interceptor.afterAllocationsWritten(command.caseId());

        String externalRequestKey = PaymentRequest.externalRequestKey(command.caseId(), snapshot.id());
        PaymentRequest paymentRequest = PaymentRequest.pending(
                UUID.randomUUID(),
                command.caseId(),
                purchaseOrderId,
                decision.id(),
                snapshot.id(),
                snapshot.evidenceBundleId(),
                externalRequestKey,
                plan.totalAmount(),
                CURRENCY,
                now);
        paymentRequests.saveAndFlush(paymentRequest);
        interceptor.afterPaymentRequestWritten(command.caseId());

        invoiceCase.transitionTo(InvoiceCaseStatus.EXPORT_PENDING, now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        ApprovalResult result = new ApprovalResult(
                command.caseId(),
                invoiceCase.status().name(),
                invoiceCase.version(),
                decision.id(),
                decision.decisionNumber(),
                snapshot.id(),
                snapshot.payloadHash(),
                paymentRequest.id(),
                externalRequestKey,
                plan.totalAmount().amount(),
                CURRENCY,
                allocations.stream()
                        .map(a -> new ApprovedAllocation(
                                a.invoiceLineNumber(), a.receiptId(), a.receiptLineId(), a.allocatedQuantity()))
                        .toList(),
                now);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", InvoiceCaseStatus.REVIEW_PENDING.name());
        before.put("caseVersion", command.expectedCaseVersion());
        before.put("reviewSnapshotId", snapshot.id().toString());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", invoiceCase.status().name());
        after.put("caseVersion", invoiceCase.version());
        after.put("decisionId", decision.id().toString());
        after.put("decisionNumber", decision.decisionNumber());
        after.put("reviewSnapshotId", snapshot.id().toString());
        after.put("reviewPayloadHash", snapshot.payloadHash());
        after.put("paymentRequestId", paymentRequest.id().toString());
        after.put("externalRequestKey", externalRequestKey);
        after.put("amount", plan.totalAmount().amount());
        after.put("currency", CURRENCY);
        after.put("allocationCount", allocations.size());
        after.put("allocatedQuantity", allocations.stream().mapToInt(ReceiptAllocation::allocatedQuantity).sum());
        after.put("allocations", allocations.stream().map(this::allocationSummary).toList());
        audit.record(new AuditEvent(
                command.caseId(),
                actor,
                AuditAction.APPROVE,
                AuditTargetType.REVIEW_DECISION,
                decision.id().toString(),
                invoiceCase.version(),
                before,
                after,
                command.requestId(),
                now));

        idempotency.recordResponse(
                SCOPE_APPROVE, resourceKey, actor.username(), command.requestId(), 200, result);
        return CommandResult.ok(result);
    }

    private MatchResult loadMatchResult(UUID caseId, ReviewSnapshot snapshot) {
        if (snapshot.matchResultId() == null) {
            throw new ReviewStateConflictException(
                    caseId, "the review snapshot has no source match result and cannot be approved");
        }
        return matchResults
                .findById(snapshot.matchResultId())
                .orElseThrow(() -> new ReviewStateConflictException(
                        caseId, "the source match result of the review snapshot no longer exists"));
    }

    private void requireSameCaseMatchResult(UUID caseId, MatchResult matchResult) {
        if (!matchResult.invoiceCaseId().equals(caseId)) {
            throw new ReviewStateConflictException(caseId, "the source match result belongs to another case");
        }
    }

    /**
     * Resolves the frozen plan to active receipt lines, locks them in the
     * deterministic order (receipt date, external receipt line id, receipt id,
     * stable UUID), recomputes {@code remaining = confirmed - committed}, and
     * fails the whole approval if any line is missing or short. No allocation is
     * written here.
     */
    private List<ResolvedAllocation> resolveAndLockReceiptLines(
            UUID caseId, ReviewSnapshot snapshot, InvoiceCase invoiceCase, ApprovedAllocationPlan plan) {
        Map<ReceiptKey, ReceiptLineSnapshot> active = new LinkedHashMap<>();
        for (ReceiptLineSnapshot row :
                receiptLines.findByPurchaseOrderIdAndActiveTrue(invoiceCase.purchaseOrder().value())) {
            active.put(new ReceiptKey(row.receiptId(), row.receiptLineId()), row);
        }

        List<ResolvedAllocation> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (PlannedReceiptAllocation planned : plan.allocations()) {
            ReceiptLineSnapshot row = active.get(new ReceiptKey(planned.receiptId(), planned.receiptLineId()));
            if (row == null) {
                missing.add(planned.receiptId() + "/" + planned.receiptLineId());
                continue;
            }
            resolved.add(new ResolvedAllocation(
                    planned.invoiceLineNumber(), row, planned.receiptDate(), planned.plannedQuantity()));
        }
        if (!missing.isEmpty()) {
            throw new ApprovalNotPermittedException(
                    caseId,
                    snapshot.id(),
                    List.of("referenced receipt lines are not active in the current purchasing snapshot: " + missing));
        }

        Map<UUID, LocalDate> receiptDateByRow = new LinkedHashMap<>();
        for (ResolvedAllocation allocation : resolved) {
            receiptDateByRow.putIfAbsent(allocation.row().id(), allocation.receiptDate());
        }

        List<ReceiptLineSnapshot> lockOrder = resolved.stream()
                .map(ResolvedAllocation::row)
                .distinct()
                .sorted(Comparator
                        .comparing((ReceiptLineSnapshot row) -> receiptDateByRow.get(row.id()))
                        .thenComparing(ReceiptLineSnapshot::receiptLineId)
                        .thenComparing(ReceiptLineSnapshot::receiptId)
                        .thenComparing(ReceiptLineSnapshot::id))
                .toList();

        Map<UUID, ReceiptLineSnapshot> locked = new LinkedHashMap<>();
        for (ReceiptLineSnapshot row : lockOrder) {
            ReceiptLineSnapshot lockedRow = receiptLines
                    .findByIdForUpdate(row.id())
                    .orElseThrow(() -> new ApprovalNotPermittedException(
                            caseId,
                            snapshot.id(),
                            List.of("referenced receipt line vanished while locking: " + row.receiptLineId())));
            locked.put(lockedRow.id(), lockedRow);
        }

        Map<UUID, Long> requested = new LinkedHashMap<>();
        for (ResolvedAllocation allocation : resolved) {
            requested.merge(allocation.row().id(), (long) allocation.plannedQuantity(), Long::sum);
        }

        List<ReceiptBalanceShortfall> shortfalls = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : requested.entrySet()) {
            ReceiptLineSnapshot row = locked.get(entry.getKey());
            long allocated = receiptAllocations.sumAllocatedQuantity(entry.getKey());
            long confirmed = row.confirmedQuantity().value();
            long remaining = confirmed - allocated;
            if (entry.getValue() > remaining) {
                shortfalls.add(new ReceiptBalanceShortfall(
                        row.receiptId(),
                        row.receiptLineId(),
                        (int) confirmed,
                        allocated,
                        remaining,
                        entry.getValue().intValue()));
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new InsufficientReceiptBalanceException(caseId, snapshot.id(), shortfalls);
        }

        return resolved.stream()
                .map(allocation -> new ResolvedAllocation(
                        allocation.invoiceLineNumber(),
                        locked.get(allocation.row().id()),
                        allocation.receiptDate(),
                        allocation.plannedQuantity()))
                .toList();
    }

    private String decisionPayload(ApprovedAllocationPlan plan) {
        ObjectNode node = mapper.createObjectNode();
        node.put("amount", plan.totalAmount().amount());
        node.put("currency", CURRENCY);
        node.put("allocationCount", plan.allocations().size());
        node.put(
                "allocatedQuantity",
                plan.allocations().stream().mapToInt(PlannedReceiptAllocation::plannedQuantity).sum());
        ArrayNode allocations = node.putArray("allocations");
        for (PlannedReceiptAllocation allocation : plan.allocations()) {
            ObjectNode item = allocations.addObject();
            item.put("invoiceLineNumber", allocation.invoiceLineNumber());
            item.put("receiptId", allocation.receiptId());
            item.put("receiptLineId", allocation.receiptLineId());
            item.put("quantity", allocation.plannedQuantity());
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Approval decision payload serialization failed", e);
        }
    }

    private Map<String, Object> allocationSummary(ReceiptAllocation allocation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("invoiceLineNumber", allocation.invoiceLineNumber());
        summary.put("receiptId", allocation.receiptId());
        summary.put("receiptLineId", allocation.receiptLineId());
        summary.put("quantity", allocation.allocatedQuantity());
        return summary;
    }

    private InvoiceCase loadForUpdate(UUID caseId) {
        return invoiceCases.findByIdForUpdate(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
    }

    private static void checkExpectedVersion(InvoiceCase invoiceCase, long expectedVersion) {
        if (invoiceCase.version() != expectedVersion) {
            throw new StaleCaseVersionException(invoiceCase.id().value(), expectedVersion, invoiceCase.version());
        }
    }

    private record ReceiptKey(String receiptId, String receiptLineId) {
    }

    private record ResolvedAllocation(
            int invoiceLineNumber, ReceiptLineSnapshot row, LocalDate receiptDate, int plannedQuantity) {
    }
}
