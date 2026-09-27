package com.invoicematch.core.matching.application;

import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;
import com.invoicematch.core.invoicecase.application.MatchCaseSnapshot;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.matching.domain.MatchResultNotFoundException;
import com.invoicematch.core.matching.domain.MatchStateConflictException;
import com.invoicematch.core.matching.persistence.MatchResultRepository;
import com.invoicematch.core.purchasingreference.application.CurrentPurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotReader;
import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates deterministic 3-way matching for one invoice case.
 *
 * <p>It loads the latest frozen evidence bundle and the current purchasing
 * snapshot, applies the current effective case-local mappings through the
 * {@link EffectiveMappingResolver} seam, runs the pure {@link MatchEngine} and
 * appends an immutable {@link MatchResult} that records the compared purchasing
 * snapshot and applied mapping watermark. A re-match with a new request id
 * appends a new result with the same canonical payload/hash; reusing a request
 * id replays the stored response. This method creates no
 * {@code ReceiptAllocation} and consumes no receipt balance.
 */
@Service
public class MatchingService {

    public static final String SCOPE_MATCH = "invoice-case:match";

    private final InvoiceCaseQueryService invoiceCaseQueries;
    private final PurchaseOrderSnapshotReader purchaseOrderSnapshots;
    private final MatchResultRepository matchResults;
    private final MatchEngine engine;
    private final MatchCommandFingerprint fingerprint;
    private final RequestIdempotencyStore idempotency;
    private final EvidenceBundlePayloadHasher bundleHasher;
    private final EffectiveMappingResolver mappingResolver;
    private final MatchLockInterceptor matchLockInterceptor;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final Clock clock;

    public MatchingService(
            InvoiceCaseQueryService invoiceCaseQueries,
            PurchaseOrderSnapshotReader purchaseOrderSnapshots,
            MatchResultRepository matchResults,
            MatchEngine engine,
            MatchCommandFingerprint fingerprint,
            RequestIdempotencyStore idempotency,
            EvidenceBundlePayloadHasher bundleHasher,
            ObjectProvider<EffectiveMappingResolver> mappingResolvers,
            ObjectProvider<MatchLockInterceptor> matchLockInterceptors,
            AuthorizationService authorization,
            AuditRecorder audit,
            Clock clock) {
        this.invoiceCaseQueries = invoiceCaseQueries;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.matchResults = matchResults;
        this.engine = engine;
        this.fingerprint = fingerprint;
        this.idempotency = idempotency;
        this.bundleHasher = bundleHasher;
        this.mappingResolver = mappingResolvers.getIfAvailable(() -> EffectiveMappingResolver.EMPTY);
        this.matchLockInterceptor = matchLockInterceptors.getIfAvailable(() -> MatchLockInterceptor.NONE);
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public CommandResult<MatchResultView> run(RunMatchCommand command) {
        // Lock the case row before reading its state or its latest bundle, and
        // hold it through the result insert and idempotency response. A
        // concurrent submit or state transition that locks the case first either
        // commits before this lock or waits for it, so this match can never mix
        // an old case version with a new bundle.
        MatchCaseSnapshot caseSnapshot = invoiceCaseQueries.loadForMatching(command.caseId());
        matchLockInterceptor.afterCaseLocked(command.caseId());
        // Authoritative role check at the transaction/lock boundary.
        authorization.requireRole(Role.OPERATOR);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.runMatch(command);
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_MATCH, resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return new CommandResult<>(
                    replay.response().status(),
                    idempotency.decode(replay.response(), MatchResultView.class));
        }
        requireReviewable(caseSnapshot);

        CurrentPurchaseOrderSnapshot purchasing = requireCurrentPurchasingSnapshot(caseSnapshot);
        MatchResultView view = appendResult(caseSnapshot, purchasing);
        audit.record(new AuditEvent(
                caseSnapshot.caseId(),
                actor,
                AuditAction.MATCH_RUN,
                AuditTargetType.MATCH_RESULT,
                view.id().toString(),
                caseSnapshot.caseVersion(),
                null,
                java.util.Map.of(
                        "resultNumber", view.resultNumber(),
                        "resultHash", view.resultHash(),
                        "evidenceBundleId", view.evidenceBundleId().toString(),
                        "evidenceBundleVersion", caseSnapshot.evidenceBundleVersion(),
                        "purchasingSnapshotVersion", view.purchasingSnapshotVersion()),
                command.requestId(),
                view.createdAt()));
        idempotency.recordResponse(SCOPE_MATCH, resourceKey, actor.username(), command.requestId(), 201, view);
        return CommandResult.created(view);
    }

    /**
     * Computes and appends a result for an already-loaded case snapshot under a
     * case row lock the caller holds (or for the plain match path). It is the
     * seam the review module uses to re-match after a mapping decision inside
     * the same transaction as the decision and its successor snapshot.
     */
    public MatchResultView appendResult(MatchCaseSnapshot caseSnapshot, CurrentPurchaseOrderSnapshot purchasing) {
        List<UUID> duplicateCaseIds = invoiceCaseQueries.findOtherCaseIdsWithBusinessInvoice(
                caseSnapshot.supplierId(), caseSnapshot.normalizedInvoiceNumber(), caseSnapshot.caseId());

        EvidenceBundlePayload bundlePayload = bundleHasher.parse(caseSnapshot.evidenceBundlePayload());
        EffectiveMappingResolver.EffectiveMappings effective =
                mappingResolver.resolve(caseSnapshot.caseId(), caseSnapshot.evidenceBundleId());

        MatchInput input = new MatchInput(
                caseSnapshot.caseId(),
                caseSnapshot.supplierId(),
                caseSnapshot.purchaseOrderId(),
                caseSnapshot.invoiceNumber(),
                caseSnapshot.normalizedInvoiceNumber(),
                caseSnapshot.caseVersion(),
                caseSnapshot.evidenceBundleId(),
                caseSnapshot.evidenceBundleVersion(),
                caseSnapshot.evidenceBundleHash(),
                bundlePayload.lines(),
                purchasing.aggregate(),
                purchasing.payloadHash(),
                duplicateCaseIds,
                effective.mappings());

        MatchComputation computation = engine.compute(input);

        // The case row lock serializes matching for this case, so max+1 is a
        // safe per-case monotonic append number for latest/history ordering.
        int resultNumber = matchResults.maxResultNumber(caseSnapshot.caseId()) + 1;

        MatchResult saved = matchResults.saveAndFlush(MatchResult.record(
                UUID.randomUUID(),
                caseSnapshot.caseId(),
                caseSnapshot.evidenceBundleId(),
                resultNumber,
                computation.resultHash(),
                purchasing.aggregate().snapshotVersion(),
                purchasing.payloadHash(),
                effective.watermark(),
                computation.canonicalJson(),
                clock.instant()));
        return MatchResultView.from(saved);
    }

    /**
     * Loads the current local purchasing snapshot or fails as a state conflict.
     * The read is local (no external HTTP) so it may run while the caller holds
     * the invoice case row lock.
     */
    public CurrentPurchaseOrderSnapshot requireCurrentPurchasingSnapshot(MatchCaseSnapshot caseSnapshot) {
        return purchaseOrderSnapshots
                .findCurrentSnapshot(PurchaseOrderId.of(caseSnapshot.purchaseOrderId()))
                .orElseThrow(() -> new MatchStateConflictException(
                        caseSnapshot.caseId(),
                        "no current purchasing snapshot exists for purchase order "
                                + caseSnapshot.purchaseOrderId()));
    }

    @Transactional(readOnly = true)
    public MatchResultView latest(UUID caseId) {
        invoiceCaseQueries.requireCase(caseId);
        return matchResults
                .findFirstByInvoiceCaseIdOrderByResultNumberDesc(caseId)
                .map(MatchResultView::from)
                .orElseThrow(() -> new MatchResultNotFoundException(caseId));
    }

    @Transactional(readOnly = true)
    public List<MatchResultView> list(UUID caseId) {
        invoiceCaseQueries.requireCase(caseId);
        return matchResults.findByInvoiceCaseIdOrderByResultNumberAsc(caseId).stream()
                .map(MatchResultView::from)
                .toList();
    }

    private static void requireReviewable(MatchCaseSnapshot caseSnapshot) {
        InvoiceCaseStatus status = caseSnapshot.status();
        if (status != InvoiceCaseStatus.REVIEW_PENDING) {
            throw new MatchStateConflictException(
                    caseSnapshot.caseId(),
                    "matching requires REVIEW_PENDING but status was " + status);
        }
    }
}
