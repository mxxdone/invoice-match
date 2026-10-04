package com.invoicematch.core.approval.application;

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
import com.invoicematch.core.payment.domain.OutboxEvent;
import com.invoicematch.core.payment.persistence.OutboxEventRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptLineSnapshotRepository;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshot;
import com.invoicematch.core.purchasingreference.persistence.ReceiptSnapshotRepository;
import com.invoicematch.core.review.application.ReviewCurrentnessService;
import com.invoicematch.core.review.domain.ReviewDecision;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import com.invoicematch.core.review.persistence.ReviewDecisionRepository;
import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.trace.TraceContext;
import com.invoicematch.core.trace.TraceId;
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
 *   <li>revalidate case state/version, exact snapshot id/hash and purchasing
 *       currentness;</li>
 *   <li>independently reconstruct the evidence bundle, match result and review
 *       snapshot from authoritative relational sources and derive a typed
 *       allocation plan and amount ({@link ApprovalSubjectVerifier});</li>
 *   <li>lock every referenced active receipt line in deterministic order after
 *       validating the frozen receipt facts;</li>
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
    private final ApprovalSubjectVerifier subjectVerifier;
    private final PurchaseOrderSnapshotRepository purchaseOrderSnapshots;
    private final PurchaseOrderSnapshotLock purchaseOrderLock;
    private final PurchasingReferenceService purchasingReferenceService;
    private final ReceiptLineSnapshotRepository receiptLines;
    private final ReceiptSnapshotRepository receiptSnapshots;
    private final ReceiptAllocationRepository receiptAllocations;
    private final PaymentRequestRepository paymentRequests;
    private final OutboxEventRepository outboxEvents;
    private final RequestIdempotencyStore idempotency;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final ApprovalInterceptor interceptor;
    private final Clock clock;
    private final com.invoicematch.core.analysis.application.GraphInvalidationService graphs;

    public ApprovalService(
            InvoiceCaseRepository invoiceCases,
            ReviewDecisionRepository decisions,
            MatchResultRepository matchResults,
            ReviewCurrentnessService currentness,
            ApprovalSubjectVerifier subjectVerifier,
            PurchaseOrderSnapshotRepository purchaseOrderSnapshots,
            PurchaseOrderSnapshotLock purchaseOrderLock,
            PurchasingReferenceService purchasingReferenceService,
            ReceiptLineSnapshotRepository receiptLines,
            ReceiptSnapshotRepository receiptSnapshots,
            ReceiptAllocationRepository receiptAllocations,
            PaymentRequestRepository paymentRequests,
            OutboxEventRepository outboxEvents,
            RequestIdempotencyStore idempotency,
            AuthorizationService authorization,
            AuditRecorder audit,
            ObjectProvider<ApprovalInterceptor> approvalInterceptors,
            Clock clock,com.invoicematch.core.analysis.application.GraphInvalidationService graphs) {
        this.invoiceCases = invoiceCases;
        this.decisions = decisions;
        this.matchResults = matchResults;
        this.currentness = currentness;
        this.subjectVerifier = subjectVerifier;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.purchaseOrderLock = purchaseOrderLock;
        this.purchasingReferenceService = purchasingReferenceService;
        this.receiptLines = receiptLines;
        this.receiptSnapshots = receiptSnapshots;
        this.receiptAllocations = receiptAllocations;
        this.paymentRequests = paymentRequests;
        this.outboxEvents = outboxEvents;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.audit = audit;
        this.interceptor = approvalInterceptors.getIfAvailable(() -> ApprovalInterceptor.NONE);
        this.clock = clock;
        this.graphs = graphs;
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

        // The authoritative current purchasing facts are exactly what this
        // transaction just applied. A REQUIRES_NEW read would not see the
        // uncommitted apply and could wrongly pass.
        PurchaseOrderSnapshot stored = purchaseOrderSnapshots
                .findById(purchaseOrderId)
                .orElseThrow(() -> new ReviewStateConflictException(
                        command.caseId(), "no current purchasing snapshot exists after refresh"));
        if (stored.snapshotVersion() != preparedSnapshot.aggregate().snapshotVersion()
                || !stored.payloadHash().equals(preparedSnapshot.canonicalHash())) {
            throw new ReviewStateConflictException(
                    command.caseId(),
                    "the current purchasing snapshot changed concurrently and was not applied by this approval");
        }
        long currentPurchasingVersion = stored.snapshotVersion();
        String currentPurchasingHash = stored.payloadHash();

        ReviewSnapshot snapshot = currentness.loadSnapshot(command.caseId(), command.reviewSnapshotId());
        if (!snapshot.payloadHash().equals(command.reviewPayloadHash())) {
            throw new ReviewStateConflictException(
                    command.caseId(),
                    "review payload hash does not match the target snapshot " + command.reviewSnapshotId());
        }
        currentness.requireCurrent(invoiceCase, snapshot, currentPurchasingVersion, currentPurchasingHash);

        MatchResult matchResult = loadMatchResult(command.caseId(), snapshot);
        VerifiedApprovalSubject verified = subjectVerifier.verify(
                invoiceCase,
                snapshot,
                matchResult,
                preparedSnapshot.aggregate(),
                currentPurchasingVersion,
                currentPurchasingHash);
        ApprovedAllocationPlan plan = verified.plan();

        List<ResolvedAllocation> resolved = resolveAndLockReceiptLines(
                command.caseId(), snapshot, purchaseOrderId, plan);

        Instant now = clock.instant();
        long plannedQuantityTotal = sumPlannedQuantity(command.caseId(), snapshot.id(), plan);
        long caseVersionBefore = invoiceCase.version();

        // Approval audit context, persisted on the immutable decision and reused
        // for the audit row so both can be checked for exact equality. Actor roles
        // are server-authenticated; the request id is the actor-scoped idempotency
        // input; the trace id is validated propagated correlation metadata, never
        // an authentication, authorization, uniqueness or approval-identity
        // credential.
        String approvalActorRoles = actor.rolesCsv();
        String approvalRequestId = command.requestId();
        String approvalTraceId = resolveTraceId();

        int decisionNumber = decisions.maxDecisionNumber(command.caseId()) + 1;
        ReviewDecision decision = ReviewDecision.recordApproval(
                UUID.randomUUID(),
                command.caseId(),
                snapshot.id(),
                decisionNumber,
                actor.username(),
                null,
                ApprovalDecisionPayload.canonicalJson(plan, plannedQuantityTotal, CURRENCY),
                snapshot.payloadHash(),
                now,
                plan.totalAmount(),
                CURRENCY,
                caseVersionBefore,
                caseVersionBefore + 1,
                approvalActorRoles,
                approvalRequestId,
                approvalTraceId);
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
                        snapshot.payloadHash(),
                        allocation.invoiceLineNumber(),
                        allocation.row().id(),
                        allocation.row().receiptId(),
                        allocation.row().receiptLineId(),
                        allocation.row().purchaseOrderLineId(),
                        allocation.row().receiptLineVersion(),
                        allocation.row().confirmedQuantity().value(),
                        allocation.plannedQuantity(),
                        now))
                .toList();
        receiptAllocations.saveAll(allocations);
        receiptAllocations.flush();
        interceptor.afterAllocationsWritten(command.caseId());

        // Canonical deterministic order for the result and the audited allocation
        // array: invoice line, then receipt line, then receipt.
        List<ReceiptAllocation> orderedAllocations = allocations.stream()
                .sorted(Comparator.comparingInt(ReceiptAllocation::invoiceLineNumber)
                        .thenComparing(ReceiptAllocation::receiptLineId)
                        .thenComparing(ReceiptAllocation::receiptId))
                .toList();
        long allocatedQuantityTotal = sumCommittedQuantity(command.caseId(), snapshot.id(), orderedAllocations);
        if (allocatedQuantityTotal != plannedQuantityTotal) {
            throw new IllegalStateException("committed allocation total does not equal the planned total");
        }

        String externalRequestKey = PaymentRequest.externalRequestKey(command.caseId(), snapshot.id());
        PaymentRequest paymentRequest = PaymentRequest.notSent(
                UUID.randomUUID(),
                command.caseId(),
                purchaseOrderId,
                decision.id(),
                snapshot.id(),
                snapshot.evidenceBundleId(),
                snapshot.payloadHash(),
                externalRequestKey,
                plan.totalAmount(),
                CURRENCY,
                now);
        paymentRequests.saveAndFlush(paymentRequest);
        interceptor.afterPaymentRequestWritten(command.caseId());

        // Same transaction: the pending export is committed with the payment, so
        // the relay can never lose a committed approval (Spec 13.1, ADR 0003).
        interceptor.beforeOutboxWritten(command.caseId());
        outboxEvents.saveAndFlush(OutboxEvent.exportRequested(paymentRequest, now));
        interceptor.afterOutboxWritten(command.caseId());

        invoiceCase.transitionTo(InvoiceCaseStatus.EXPORT_PENDING, now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);
        graphs.invalidateCase(command.caseId());
        if (invoiceCase.version() != caseVersionBefore + 1) {
            throw new IllegalStateException("approved case version did not advance by exactly one");
        }
        interceptor.afterCaseTransitioned(command.caseId());

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
                orderedAllocations.stream()
                        .map(a -> new ApprovedAllocation(
                                a.invoiceLineNumber(), a.receiptId(), a.receiptLineId(), a.allocatedQuantity()))
                        .toList(),
                now);

        audit.record(new AuditEvent(
                command.caseId(),
                actor,
                AuditAction.APPROVE,
                AuditTargetType.REVIEW_DECISION,
                decision.id().toString(),
                invoiceCase.version(),
                ApprovalAuditPayloads.before(caseVersionBefore, snapshot),
                ApprovalAuditPayloads.after(result, allocatedQuantityTotal),
                approvalRequestId,
                now,
                approvalTraceId));
        interceptor.afterAuditRecorded(command.caseId());

        idempotency.recordResponse(
                SCOPE_APPROVE, resourceKey, actor.username(), command.requestId(), 200, result);
        interceptor.afterIdempotencyRecorded(command.caseId());
        return CommandResult.ok(result);
    }

    private MatchResult loadMatchResult(UUID caseId, ReviewSnapshot snapshot) {
        if (snapshot.matchResultId() == null) {
            throw new ReviewStateConflictException(
                    caseId, "the review snapshot has no source match result and cannot be approved");
        }
        MatchResult matchResult = matchResults
                .findById(snapshot.matchResultId())
                .orElseThrow(() -> new ReviewStateConflictException(
                        caseId, "the source match result of the review snapshot no longer exists"));
        if (!matchResult.invoiceCaseId().equals(caseId)) {
            throw new ReviewStateConflictException(caseId, "the source match result belongs to another case");
        }
        return matchResult;
    }

    /**
     * Validates the frozen plan against the current receipt facts, locks every
     * referenced active receipt line in the deterministic order (receipt date,
     * external receipt line id, receipt id, stable UUID), recomputes
     * {@code remaining = confirmed - committed}, and fails the whole approval if
     * any line is missing, mismatched or short. No allocation is written here.
     */
    private List<ResolvedAllocation> resolveAndLockReceiptLines(
            UUID caseId, ReviewSnapshot snapshot, String purchaseOrderId, ApprovedAllocationPlan plan) {
        Map<ReceiptKey, ReceiptLineSnapshot> active = new LinkedHashMap<>();
        for (ReceiptLineSnapshot row : receiptLines.findByPurchaseOrderIdAndActiveTrue(purchaseOrderId)) {
            active.put(new ReceiptKey(row.receiptId(), row.receiptLineId()), row);
        }
        Map<String, LocalDate> receiptDateByReceipt = new LinkedHashMap<>();
        for (ReceiptSnapshot receipt : receiptSnapshots.findByPurchaseOrderIdAndActiveTrue(purchaseOrderId)) {
            receiptDateByReceipt.put(receipt.receiptId(), receipt.receiptDate());
        }

        List<ResolvedAllocation> resolved = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (PlannedReceiptAllocation planned : plan.allocations()) {
            ReceiptLineSnapshot row = active.get(new ReceiptKey(planned.receiptId(), planned.receiptLineId()));
            if (row == null) {
                mismatches.add("receipt line is not active: " + planned.receiptId() + "/" + planned.receiptLineId());
                continue;
            }
            LocalDate currentDate = receiptDateByReceipt.get(row.receiptId());
            if (!row.purchaseOrderId().equals(purchaseOrderId)
                    || !row.purchaseOrderLineId().equals(planned.purchaseOrderLineId())
                    || row.receiptLineVersion() != planned.receiptLineVersion()
                    || row.confirmedQuantity().value() != planned.confirmedQuantity()
                    || currentDate == null
                    || !currentDate.equals(planned.receiptDate())) {
                mismatches.add("frozen receipt fact does not match current purchasing facts: "
                        + planned.receiptId() + "/" + planned.receiptLineId());
                continue;
            }
            resolved.add(new ResolvedAllocation(
                    planned.invoiceLineNumber(), row, planned.receiptDate(), planned.plannedQuantity()));
        }
        if (!mismatches.isEmpty()) {
            throw new ApprovalNotPermittedException(
                    caseId, snapshot.id(), mismatches.stream().distinct().toList());
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
                        confirmed,
                        allocated,
                        remaining,
                        entry.getValue()));
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

    private long sumPlannedQuantity(UUID caseId, UUID snapshotId, ApprovedAllocationPlan plan) {
        try {
            return ApprovalAggregates.sumPlannedQuantity(plan.allocations());
        } catch (ArithmeticException overflow) {
            throw new ApprovalNotPermittedException(
                    caseId, snapshotId, List.of("the approved allocation quantity total overflows"));
        }
    }

    private long sumCommittedQuantity(UUID caseId, UUID snapshotId, List<ReceiptAllocation> allocations) {
        try {
            return ApprovalAggregates.sumAllocatedQuantity(allocations);
        } catch (ArithmeticException overflow) {
            // Defensive: the planned total already fits in a long and every
            // committed allocation mirrors the plan, so this is a stable failure
            // contract rather than a raw overflow escaping the write.
            throw new ApprovalNotPermittedException(
                    caseId, snapshotId, List.of("the committed allocation quantity total overflows"));
        }
    }

    private InvoiceCase loadForUpdate(UUID caseId) {
        return invoiceCases.findByIdForUpdate(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
    }

    private static String resolveTraceId() {
        String current = TraceContext.current();
        return current != null ? current : TraceId.generate();
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
