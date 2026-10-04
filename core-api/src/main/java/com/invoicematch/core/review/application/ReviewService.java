package com.invoicematch.core.review.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.domain.StaleCaseVersionException;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.matching.persistence.MatchResultRepository;
import com.invoicematch.core.purchasingreference.application.CurrentPurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotLock;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotReader;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.review.domain.ReviewDecision;
import com.invoicematch.core.review.domain.ReviewDecisionType;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import com.invoicematch.core.review.domain.ReviewTargetInvalidException;
import com.invoicematch.core.review.persistence.ReviewDecisionRepository;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional write side of the human review workflow.
 *
 * <p>Every method reserves its request id in the same transaction as its side
 * effects. Human actions target exactly the snapshot the reviewer saw
 * ({@code reviewSnapshotId} + {@code reviewPayloadHash} + {@code
 * expectedCaseVersion}) and are rejected with 409 and no side effects when the
 * target is stale, superseded or mismatched. Mapping decisions bump the case
 * version, deterministically re-match with every effective case-local mapping
 * and freeze a successor snapshot, all under the invoice case row lock so the
 * lock order stays case first then children and no external HTTP is made while
 * a lock is held.
 */
@Service
public class ReviewService {

    public static final String SCOPE_SNAPSHOT = "invoice-case:review-snapshot";
    public static final String SCOPE_MAPPING = "invoice-case:mapping";
    public static final String SCOPE_SUPPLEMENT = "invoice-case:supplement";
    public static final String SCOPE_REJECT = "invoice-case:reject";
    private static final int MAX_REASON_LENGTH = 1000;

    private final InvoiceCaseRepository invoiceCases;
    private final EvidenceBundleRepository evidenceBundles;
    private final MatchResultRepository matchResults;
    private final ReviewSnapshotRepository snapshots;
    private final ReviewDecisionRepository decisions;
    private final InternalMatchRematch internalRematch;
    private final ReviewEffectiveMappingResolver mappingResolver;
    private final ReviewCurrentnessService currentness;
    private final ReviewSnapshotPayloadBuilder payloadBuilder;
    private final EvidenceBundlePayloadHasher bundleHasher;
    private final PurchaseOrderSnapshotReader purchaseOrderSnapshots;
    private final PurchaseOrderSnapshotLock purchaseOrderLock;
    private final ReviewLockInterceptor reviewLockInterceptor;
    private final ReviewCommandFingerprint fingerprint;
    private final RequestIdempotencyStore idempotency;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;
    private final ProposalEvidenceReader proposals;

    public ReviewService(
            InvoiceCaseRepository invoiceCases,
            EvidenceBundleRepository evidenceBundles,
            MatchResultRepository matchResults,
            ReviewSnapshotRepository snapshots,
            ReviewDecisionRepository decisions,
            InternalMatchRematch internalRematch,
            ReviewEffectiveMappingResolver mappingResolver,
            ReviewCurrentnessService currentness,
            ReviewSnapshotPayloadBuilder payloadBuilder,
            EvidenceBundlePayloadHasher bundleHasher,
            PurchaseOrderSnapshotReader purchaseOrderSnapshots,
            PurchaseOrderSnapshotLock purchaseOrderLock,
            ObjectProvider<ReviewLockInterceptor> reviewLockInterceptors,
            ReviewCommandFingerprint fingerprint,
            RequestIdempotencyStore idempotency,
            AuthorizationService authorization,
            AuditRecorder audit,
            Clock clock,ProposalEvidenceReader proposals) {
        this.invoiceCases = invoiceCases;
        this.evidenceBundles = evidenceBundles;
        this.matchResults = matchResults;
        this.snapshots = snapshots;
        this.decisions = decisions;
        this.internalRematch = internalRematch;
        this.mappingResolver = mappingResolver;
        this.currentness = currentness;
        this.payloadBuilder = payloadBuilder;
        this.bundleHasher = bundleHasher;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.purchaseOrderLock = purchaseOrderLock;
        this.reviewLockInterceptor = reviewLockInterceptors.getIfAvailable(() -> ReviewLockInterceptor.NONE);
        this.fingerprint = fingerprint;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;this.proposals=proposals;
    }

    @Transactional
    public CommandResult<ReviewSnapshotView> freezeSnapshot(FreezeReviewSnapshotCommand command) {
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireRole(Role.APPROVER);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.freezeSnapshot(command);
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_SNAPSHOT, resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), ReviewSnapshotView.class);
        }

        requireReviewPending(invoiceCase);
        beginWrite(invoiceCase, command.expectedCaseVersion());

        EvidenceBundle bundle = latestBundle(command.caseId());
        MatchResult result = latestResultForBundle(command.caseId(), bundle.id());
        CurrentPurchaseOrderSnapshot purchasing = requireCurrentPurchasing(invoiceCase);

        verifyResultIsCurrent(result, purchasing);

        if((command.proposalId()==null)!=(command.proposalHash()==null)) throw new ReviewStateConflictException(command.caseId(),"Both advisory proposal identity and hash are required");
        ProposalEvidenceReader.Reference proof=command.proposalId()==null?null:proposals.verify(command.caseId(),bundle.id(),result.id(),command.proposalId(),command.proposalHash());
        int snapshotNumber = snapshots.maxSnapshotNumber(command.caseId()) + 1;
        ReviewSnapshot snapshot = buildAndSaveSnapshot(
                invoiceCase,
                bundle,
                result,
                mappingResolver.resolve(command.caseId(), bundle.id()).mappings(),
                purchasing,
                snapshotNumber,
                clock.instant(),proof);

        ReviewSnapshotView view = ReviewSnapshotView.from(snapshot);
        audit.record(new AuditEvent(
                command.caseId(),
                actor,
                AuditAction.REVIEW_SNAPSHOT_FROZEN,
                AuditTargetType.REVIEW_SNAPSHOT,
                snapshot.id().toString(),
                invoiceCase.version(),
                null,
                java.util.Map.of(
                        "snapshotNumber", snapshot.snapshotNumber(),
                        "payloadHash", snapshot.payloadHash(),
                        "matchResultNumber", result.resultNumber(),
                        "evidenceBundleVersion", bundle.versionNumber()),
                command.requestId(),
                snapshot.createdAt()));
        idempotency.recordResponse(
                SCOPE_SNAPSHOT, resourceKey, actor.username(), command.requestId(), 201, view);
        return CommandResult.created(view);
    }

    @Transactional
    public CommandResult<MappingDecisionResult> recordMapping(RecordMappingDecisionCommand command) {
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireRole(Role.APPROVER);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.recordMapping(command);
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_MAPPING, resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), MappingDecisionResult.class);
        }

        requireReviewPending(invoiceCase);
        beginWrite(invoiceCase, command.expectedCaseVersion());

        ReviewSnapshot target = requireTarget(invoiceCase, command.reviewSnapshotId(), command.reviewPayloadHash());
        currentness.requireCurrent(invoiceCase, target);

        EvidenceBundle bundle = evidenceBundles
                .findById(target.evidenceBundleId())
                .orElseThrow(() -> new ReviewStateConflictException(
                        command.caseId(), "target evidence bundle no longer exists"));
        requireLineExists(bundle, command.lineNumber());
        CurrentPurchaseOrderSnapshot purchasing = requireCurrentPurchasing(invoiceCase);
        String purchaseOrderLineId =
                resolveUniquePurchaseOrderLine(command.caseId(), purchasing, command.itemId());

        // Capture the effective mapping for this line before the new decision, so
        // the audit diff shows the exact replacement.
        Object previousMapping = previousMappingForLine(command.caseId(), bundle.id(), command.lineNumber());

        Instant now = clock.instant();
        int decisionNumber = decisions.maxDecisionNumber(command.caseId()) + 1;
        ReviewDecision decision = ReviewDecision.recordMapping(
                UUID.randomUUID(),
                command.caseId(),
                target.id(),
                decisionNumber,
                actor.username(),
                null,
                mappingPayload(command.lineNumber(), command.itemId(), purchaseOrderLineId),
                target.payloadHash(),
                now,
                bundle.id(),
                command.lineNumber(),
                command.itemId(),
                purchaseOrderLineId);
        decisions.saveAndFlush(decision);

        // Bump the case version before the successor is frozen so every prior
        // snapshot is stale by the time the successor exists.
        invoiceCase.markModified(now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        // Internal deterministic re-match, executed by a package-private
        // collaborator inside this authorized, case-locked, idempotent mapping
        // transaction. Its result is covered by the atomic ITEM_MAPPED audit
        // below rather than a separate operator-style MATCH_RUN.
        MatchResult successorResult = internalRematch.append(command.caseId());

        int snapshotNumber = snapshots.maxSnapshotNumber(command.caseId()) + 1;
        ReviewSnapshot successor = buildAndSaveSnapshot(
                invoiceCase,
                bundle,
                successorResult,
                mappingResolver.resolve(command.caseId(), bundle.id()).mappings(),
                purchasing,
                snapshotNumber,
                clock.instant());

        MappingDecisionResult response =
                new MappingDecisionResult(ReviewDecisionView.from(decision), ReviewSnapshotView.from(successor));
        audit.record(new AuditEvent(
                command.caseId(),
                actor,
                AuditAction.ITEM_MAPPED,
                AuditTargetType.REVIEW_DECISION,
                decision.id().toString(),
                invoiceCase.version(),
                previousMapping,
                java.util.Map.of(
                        "lineNumber", command.lineNumber(),
                        "itemId", command.itemId(),
                        "purchaseOrderLineId", purchaseOrderLineId,
                        "decisionNumber", decision.decisionNumber(),
                        "successorSnapshotNumber", successor.snapshotNumber(),
                        "successorMatchResultId", successorResult.id().toString(),
                        "successorMatchResultNumber", successorResult.resultNumber(),
                        "successorMatchResultHash", successorResult.resultHash()),
                command.requestId(),
                now));
        idempotency.recordResponse(SCOPE_MAPPING, resourceKey, actor.username(), command.requestId(), 200, response);
        return CommandResult.ok(response);
    }

    @Transactional
    public CommandResult<ReviewDecisionView> requestSupplement(RequestSupplementCommand command) {
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireRole(Role.APPROVER);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.requestSupplement(command);
        SimpleDecisionOperation operation = SimpleDecisionOperation.SUPPLEMENT;
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                operation.scope(), resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), ReviewDecisionView.class);
        }

        ReviewSnapshot target = prepareSimpleDecision(invoiceCase, command.expectedCaseVersion(),
                command.reviewSnapshotId(), command.reviewPayloadHash());
        String reason = requireReason(command.reason());

        return completeSimpleDecision(
                invoiceCase, actor, target, reason, resourceKey, command.requestId(), operation);
    }

    @Transactional
    public CommandResult<ReviewDecisionView> reject(RejectReviewCommand command) {
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireRole(Role.APPROVER);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.reject(command);
        SimpleDecisionOperation operation = SimpleDecisionOperation.REJECT;
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                operation.scope(), resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), ReviewDecisionView.class);
        }

        ReviewSnapshot target = prepareSimpleDecision(invoiceCase, command.expectedCaseVersion(),
                command.reviewSnapshotId(), command.reviewPayloadHash());
        String reason = requireReason(command.reason());

        return completeSimpleDecision(
                invoiceCase, actor, target, reason, resourceKey, command.requestId(), operation);
    }

    private Object previousMappingForLine(UUID caseId, UUID evidenceBundleId, int lineNumber) {
        return mappingResolver.resolve(caseId, evidenceBundleId).mappings().stream()
                .filter(mapping -> mapping.lineNumber() == lineNumber)
                .findFirst()
                .<Object>map(mapping -> {
                    java.util.Map<String, Object> node = new java.util.LinkedHashMap<>();
                    node.put("lineNumber", mapping.lineNumber());
                    node.put("itemId", mapping.itemId());
                    node.put("purchaseOrderLineId", mapping.purchaseOrderLineId());
                    return node;
                })
                .orElse(null);
    }

    /**
     * Shared validation and preparation of a simple terminal decision: the case
     * must be editable in REVIEW_PENDING at the expected version, and the target
     * snapshot must match the displayed hash and still be current. Both
     * {@code requestSupplement} and {@code reject} call this before persisting.
     */
    private ReviewSnapshot prepareSimpleDecision(
            InvoiceCase invoiceCase, long expectedVersion, UUID reviewSnapshotId, String reviewPayloadHash) {
        requireReviewPending(invoiceCase);
        beginWrite(invoiceCase, expectedVersion);
        ReviewSnapshot target = requireTarget(invoiceCase, reviewSnapshotId, reviewPayloadHash);
        currentness.requireCurrent(invoiceCase, target);
        return target;
    }

    /**
     * The fixed attributes of one simple terminal decision. The same descriptor
     * is used at the idempotency reservation and at completion, so the scope can
     * never drift between the two. It is an explicit enum of two operations, not
     * a boolean-controlled generic flow.
     */
    private enum SimpleDecisionOperation {
        SUPPLEMENT(
                SCOPE_SUPPLEMENT,
                AuditAction.SUPPLEMENT_REQUESTED,
                ReviewDecisionType.SUPPLEMENT_REQUESTED,
                InvoiceCaseStatus.SUPPLEMENT_REQUIRED),
        REJECT(
                SCOPE_REJECT,
                AuditAction.CASE_REJECTED,
                ReviewDecisionType.REJECTED,
                InvoiceCaseStatus.REJECTED);

        private final String scope;
        private final AuditAction auditAction;
        private final ReviewDecisionType decisionType;
        private final InvoiceCaseStatus targetStatus;

        SimpleDecisionOperation(
                String scope,
                AuditAction auditAction,
                ReviewDecisionType decisionType,
                InvoiceCaseStatus targetStatus) {
            this.scope = scope;
            this.auditAction = auditAction;
            this.decisionType = decisionType;
            this.targetStatus = targetStatus;
        }

        String scope() {
            return scope;
        }
    }

    /**
     * Shared persistence and audit of one simple terminal decision. The
     * operation descriptor carries the decision type, target case status, audit
     * action and idempotency scope, so each public method stays a readable,
     * explicit flow with no boolean flag.
     */
    private CommandResult<ReviewDecisionView> completeSimpleDecision(
            InvoiceCase invoiceCase,
            Actor actor,
            ReviewSnapshot target,
            String reason,
            String resourceKey,
            String requestId,
            SimpleDecisionOperation operation) {
        Instant now = clock.instant();
        ReviewDecision decision = recordSimpleDecision(
                invoiceCase.id().value(), target, operation.decisionType, actor.username(), reason, now);
        decisions.saveAndFlush(decision);

        invoiceCase.transitionTo(operation.targetStatus, now);
        invoiceCases.saveAndFlush(invoiceCase);

        ReviewDecisionView view = ReviewDecisionView.from(decision);
        audit.record(new AuditEvent(
                invoiceCase.id().value(),
                actor,
                operation.auditAction,
                AuditTargetType.REVIEW_DECISION,
                decision.id().toString(),
                invoiceCase.version(),
                java.util.Map.of("status", "REVIEW_PENDING"),
                java.util.Map.of(
                        "status", invoiceCase.status().name(),
                        "decisionNumber", decision.decisionNumber(),
                        "reason", reason,
                        "reviewSnapshotId", target.id().toString()),
                requestId,
                now));
        idempotency.recordResponse(operation.scope(), resourceKey, actor.username(), requestId, 200, view);
        return CommandResult.ok(view);
    }

    private ReviewDecision recordSimpleDecision(
            UUID caseId,
            ReviewSnapshot target,
            ReviewDecisionType type,
            String decidedBy,
            String reason,
            Instant now) {
        int decisionNumber = decisions.maxDecisionNumber(caseId) + 1;
        return ReviewDecision.record(
                UUID.randomUUID(),
                caseId,
                target.id(),
                decisionNumber,
                type,
                decidedBy,
                reason,
                reasonPayload(reason),
                target.payloadHash(),
                now);
    }

    private ReviewSnapshot buildAndSaveSnapshot(InvoiceCase invoiceCase,EvidenceBundle bundle,MatchResult result,
            List<com.invoicematch.core.matching.application.AppliedMapping> appliedMappings,CurrentPurchaseOrderSnapshot purchasing,int snapshotNumber,Instant now) {
        return buildAndSaveSnapshot(invoiceCase,bundle,result,appliedMappings,purchasing,snapshotNumber,now,null);
    }
    private ReviewSnapshot buildAndSaveSnapshot(
            InvoiceCase invoiceCase,
            EvidenceBundle bundle,
            MatchResult result,
            List<com.invoicematch.core.matching.application.AppliedMapping> appliedMappings,
            CurrentPurchaseOrderSnapshot purchasing,
            int snapshotNumber,
            Instant now,ProposalEvidenceReader.Reference proof) {
        EvidenceBundlePayload bundlePayload = bundleHasher.parse(bundle.payload());
        ReviewSnapshotPayloadInput input = new ReviewSnapshotPayloadInput(
                invoiceCase.id().value(),
                invoiceCase.version(),
                bundle.id(),
                bundle.versionNumber(),
                bundle.payloadHash(),
                result.id(),
                result.resultNumber(),
                result.resultHash(),
                result.mappingWatermark(),
                result.payload(),
                appliedMappings,
                bundlePayload.lines(),
                purchasing.aggregate().snapshotVersion(),
                purchasing.aggregate().purchaseOrder().version(),
                purchasing.payloadHash()).withProposal(proof);
        ReviewSnapshotPayloadBuilder.CanonicalPayload canonical = payloadBuilder.canonicalize(input);
        ReviewSnapshot snapshot = ReviewSnapshot.freeze(
                UUID.randomUUID(),
                invoiceCase.id().value(),
                bundle.id(),
                result.id(),
                result.resultNumber(),
                snapshotNumber,
                invoiceCase.version(),
                bundle.versionNumber(),
                purchasing.aggregate().snapshotVersion(),
                purchasing.payloadHash(),
                result.mappingWatermark(),
                canonical.hash(),
                canonical.json(),
                now);
        return snapshots.saveAndFlush(snapshot);
    }

    private ReviewSnapshot requireTarget(InvoiceCase invoiceCase, UUID reviewSnapshotId, String reviewPayloadHash) {
        ReviewSnapshot snapshot = currentness.loadSnapshot(invoiceCase.id().value(), reviewSnapshotId);
        if (reviewPayloadHash == null || !snapshot.payloadHash().equals(reviewPayloadHash)) {
            throw new ReviewStateConflictException(
                    invoiceCase.id().value(),
                    "review payload hash does not match the target snapshot " + reviewSnapshotId);
        }
        return snapshot;
    }

    private void requireLineExists(EvidenceBundle bundle, int lineNumber) {
        EvidenceBundlePayload payload = bundleHasher.parse(bundle.payload());
        boolean exists = payload.lines().stream().anyMatch(line -> line.lineNumber() == lineNumber);
        if (!exists) {
            throw new ReviewTargetInvalidException(
                    bundle.invoiceCaseId(),
                    "invoice line " + lineNumber + " is not part of the target evidence bundle "
                            + bundle.versionNumber());
        }
    }

    private String resolveUniquePurchaseOrderLine(
            UUID caseId, CurrentPurchaseOrderSnapshot purchasing, String itemId) {
        List<PurchaseOrderLineFacts> candidates = purchasing.aggregate().purchaseOrder().lines().stream()
                .filter(line -> line.itemId().equals(itemId))
                .toList();
        if (candidates.size() != 1) {
            throw new ReviewTargetInvalidException(
                    caseId,
                    "item " + itemId + " resolves to " + candidates.size()
                            + " active purchase order lines of the current purchase order");
        }
        return candidates.get(0).purchaseOrderLineId();
    }

    private CurrentPurchaseOrderSnapshot requireCurrentPurchasing(InvoiceCase invoiceCase) {
        return purchaseOrderSnapshots
                .findCurrentSnapshot(PurchaseOrderId.of(invoiceCase.purchaseOrder().value()))
                .orElseThrow(() -> new ReviewStateConflictException(
                        invoiceCase.id().value(),
                        "no current purchasing snapshot exists for purchase order "
                                + invoiceCase.purchaseOrder().value()));
    }

    private void verifyResultIsCurrent(MatchResult result, CurrentPurchaseOrderSnapshot purchasing) {
        if (result.purchasingSnapshotVersion() != purchasing.aggregate().snapshotVersion()
                || !result.purchasingSnapshotHash().equals(purchasing.payloadHash())) {
            throw new ReviewStateConflictException(
                    result.invoiceCaseId(),
                    "the latest match result was computed against a different purchasing snapshot;"
                            + " re-run matching before freezing a review snapshot");
        }
        int currentWatermark = mappingResolver
                .resolve(result.invoiceCaseId(), result.evidenceBundleId())
                .watermark();
        if (currentWatermark != result.mappingWatermark()) {
            throw new ReviewStateConflictException(
                    result.invoiceCaseId(),
                    "the latest match result does not reflect the current effective mappings;"
                            + " re-run matching before freezing a review snapshot");
        }
    }

    private EvidenceBundle latestBundle(UUID caseId) {
        return evidenceBundles
                .findFirstByInvoiceCaseIdOrderByVersionNumberDesc(caseId)
                .orElseThrow(() -> new ReviewStateConflictException(caseId, "no frozen evidence bundle exists"));
    }

    private MatchResult latestResultForBundle(UUID caseId, UUID evidenceBundleId) {
        return matchResults
                .findFirstByInvoiceCaseIdAndEvidenceBundleIdOrderByResultNumberDesc(caseId, evidenceBundleId)
                .orElseThrow(() -> new ReviewStateConflictException(
                        caseId, "no match result exists for the latest evidence bundle"));
    }

    private InvoiceCase loadForUpdate(UUID caseId) {
        return invoiceCases.findByIdForUpdate(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
    }

    private static void requireReviewPending(InvoiceCase invoiceCase) {
        if (invoiceCase.status() != InvoiceCaseStatus.REVIEW_PENDING) {
            throw new ReviewStateConflictException(
                    invoiceCase.id().value(),
                    "the review workflow requires REVIEW_PENDING but status was " + invoiceCase.status());
        }
    }

    private static void checkExpectedVersion(InvoiceCase invoiceCase, long expectedVersion) {
        if (invoiceCase.version() != expectedVersion) {
            throw new StaleCaseVersionException(invoiceCase.id().value(), expectedVersion, invoiceCase.version());
        }
    }

    /**
     * Starts a review write under the case row lock and the purchase order
     * advisory lock. The advisory lock is held from here to commit, so a
     * concurrent purchasing refresh cannot change the snapshot between the
     * final currentness validation and the committed decision/snapshot. The lock
     * order is always invoice case first, then purchase order; no other writer
     * takes them in the opposite order, so this cannot deadlock.
     */
    private void beginWrite(InvoiceCase invoiceCase, long expectedVersion) {
        checkExpectedVersion(invoiceCase, expectedVersion);
        purchaseOrderLock.acquireXactLock(invoiceCase.purchaseOrder().value());
        reviewLockInterceptor.afterPurchaseOrderLocked(invoiceCase.id().value());
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DomainValidationException("reason must not be blank");
        }
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new DomainValidationException("reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }

    private String mappingPayload(int lineNumber, String itemId, String purchaseOrderLineId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("lineNumber", lineNumber);
        node.put("itemId", itemId);
        node.put("purchaseOrderLineId", purchaseOrderLineId);
        return write(node);
    }

    private String reasonPayload(String reason) {
        ObjectNode node = mapper.createObjectNode();
        node.put("reason", reason);
        return write(node);
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Review decision payload serialization failed", e);
        }
    }

    private <T> CommandResult<T> replay(RequestIdempotencyStore.StoredResponse stored, Class<T> type) {
        return new CommandResult<>(stored.status(), idempotency.decode(stored, type));
    }
}
