package com.invoicematch.core.matching.application;

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
 * snapshot, runs the pure {@link MatchEngine} and appends an immutable
 * {@link MatchResult}. A re-match with a new request id appends a new result
 * with the same canonical payload/hash; reusing a request id replays the stored
 * response. This method creates no {@code ReceiptAllocation} and consumes no
 * receipt balance.
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
    private final MatchLockInterceptor matchLockInterceptor;
    private final Clock clock;

    public MatchingService(
            InvoiceCaseQueryService invoiceCaseQueries,
            PurchaseOrderSnapshotReader purchaseOrderSnapshots,
            MatchResultRepository matchResults,
            MatchEngine engine,
            MatchCommandFingerprint fingerprint,
            RequestIdempotencyStore idempotency,
            EvidenceBundlePayloadHasher bundleHasher,
            ObjectProvider<MatchLockInterceptor> matchLockInterceptors,
            Clock clock) {
        this.invoiceCaseQueries = invoiceCaseQueries;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.matchResults = matchResults;
        this.engine = engine;
        this.fingerprint = fingerprint;
        this.idempotency = idempotency;
        this.bundleHasher = bundleHasher;
        this.matchLockInterceptor = matchLockInterceptors.getIfAvailable(() -> MatchLockInterceptor.NONE);
        this.clock = clock;
    }

    @Transactional
    public CommandResult<MatchResultView> run(RunMatchCommand command) {
        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.runMatch(command);
        RequestIdempotencyStore.BeginResult begin =
                idempotency.begin(SCOPE_MATCH, resourceKey, command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return new CommandResult<>(
                    replay.response().status(),
                    idempotency.decode(replay.response(), MatchResultView.class));
        }

        // Lock the case row before reading its state or its latest bundle, and
        // hold it through the result insert and idempotency response. A
        // concurrent submit or state transition that locks the case first either
        // commits before this lock or waits for it, so this match can never mix
        // an old case version with a new bundle.
        MatchCaseSnapshot caseSnapshot = invoiceCaseQueries.loadForMatching(command.caseId());
        matchLockInterceptor.afterCaseLocked(command.caseId());
        requireReviewable(caseSnapshot);

        CurrentPurchaseOrderSnapshot purchasing = purchaseOrderSnapshots
                .findCurrentSnapshot(PurchaseOrderId.of(caseSnapshot.purchaseOrderId()))
                .orElseThrow(() -> new MatchStateConflictException(
                        command.caseId(),
                        "no current purchasing snapshot exists for purchase order "
                                + caseSnapshot.purchaseOrderId()));

        List<UUID> duplicateCaseIds = invoiceCaseQueries.findOtherCaseIdsWithBusinessInvoice(
                caseSnapshot.supplierId(), caseSnapshot.normalizedInvoiceNumber(), caseSnapshot.caseId());

        EvidenceBundlePayload bundlePayload = bundleHasher.parse(caseSnapshot.evidenceBundlePayload());

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
                duplicateCaseIds);

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
                computation.canonicalJson(),
                clock.instant()));

        MatchResultView view = MatchResultView.from(saved);
        idempotency.recordResponse(SCOPE_MATCH, resourceKey, command.requestId(), 201, view);
        return CommandResult.created(view);
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
