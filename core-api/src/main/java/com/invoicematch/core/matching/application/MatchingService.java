package com.invoicematch.core.matching.application;

import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
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
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates explicit deterministic 3-way matching for one invoice case.
 *
 * <p>{@link #run} is the only public write here: it locks the authoritative
 * case, enforces OPERATOR, is request-id idempotent, appends an immutable
 * {@link MatchResult} and records a {@code MATCH_RUN} audit. The actual match is
 * computed by the pure {@link MatchResultPlanner}; the append is private. There
 * is no public raw "append a match result" seam.
 *
 * <p>The review mapping flow does not use this service to re-match. It owns its
 * own package-private internal re-match collaborator, so an arbitrary component
 * with an APPROVER context cannot append results outside a mapping decision.
 */
@Service
public class MatchingService {

    public static final String SCOPE_MATCH = "invoice-case:match";

    private final InvoiceCaseQueryService invoiceCaseQueries;
    private final PurchaseOrderSnapshotReader purchaseOrderSnapshots;
    private final MatchResultRepository matchResults;
    private final MatchResultPlanner planner;
    private final MatchCommandFingerprint fingerprint;
    private final RequestIdempotencyStore idempotency;
    private final MatchLockInterceptor matchLockInterceptor;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final Clock clock;
    private final com.invoicematch.core.analysis.application.GraphInvalidationService graphs;

    public MatchingService(
            InvoiceCaseQueryService invoiceCaseQueries,
            PurchaseOrderSnapshotReader purchaseOrderSnapshots,
            MatchResultRepository matchResults,
            MatchResultPlanner planner,
            MatchCommandFingerprint fingerprint,
            RequestIdempotencyStore idempotency,
            ObjectProvider<MatchLockInterceptor> matchLockInterceptors,
            AuthorizationService authorization,
            AuditRecorder audit,
            Clock clock,com.invoicematch.core.analysis.application.GraphInvalidationService graphs) {
        this.invoiceCaseQueries = invoiceCaseQueries;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.matchResults = matchResults;
        this.planner = planner;
        this.fingerprint = fingerprint;
        this.idempotency = idempotency;
        this.matchLockInterceptor = matchLockInterceptors.getIfAvailable(() -> MatchLockInterceptor.NONE);
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
        this.graphs = graphs;
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
        graphs.invalidateCase(command.caseId());
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
     * Computes and appends a result for an already-loaded, locked case snapshot.
     * Private: the only caller is {@link #run}, which has already enforced
     * OPERATOR and idempotency and audits the result.
     */
    private MatchResultView appendResult(MatchCaseSnapshot caseSnapshot, CurrentPurchaseOrderSnapshot purchasing) {
        PlannedMatch planned = planner.plan(caseSnapshot, purchasing);
        // The case row lock serializes matching for this case, so max+1 is a
        // safe per-case monotonic append number for latest/history ordering.
        int resultNumber = matchResults.maxResultNumber(caseSnapshot.caseId()) + 1;
        MatchResult saved = matchResults.saveAndFlush(MatchResult.record(
                UUID.randomUUID(),
                caseSnapshot.caseId(),
                caseSnapshot.evidenceBundleId(),
                resultNumber,
                planned.resultHash(),
                planned.purchasingSnapshotVersion(),
                planned.purchasingSnapshotHash(),
                planned.mappingWatermark(),
                planned.canonicalJson(),
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
