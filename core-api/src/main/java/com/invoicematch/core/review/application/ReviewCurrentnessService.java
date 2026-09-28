package com.invoicematch.core.review.application;

import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.matching.persistence.MatchResultRepository;
import com.invoicematch.core.purchasingreference.application.CurrentPurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotReader;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.domain.ReviewSnapshotNotFoundException;
import com.invoicematch.core.review.domain.StaleReason;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Evaluates whether a review snapshot is the current, fresh review subject.
 * The comparison is deliberately exhaustive and explicit: every changing input
 * (case state/version, latest bundle, latest result, effective mapping
 * watermark and purchasing snapshot version/hash) contributes its own stale
 * reason, and a target that a newer snapshot superseded is flagged too.
 *
 * <p>This is the application-level {@code require-current} contract P1-07
 * approval can call before allocating. It performs local reads only and never
 * holds a lock across external HTTP.
 */
@Service
public class ReviewCurrentnessService {

    private final InvoiceCaseRepository invoiceCases;
    private final EvidenceBundleRepository evidenceBundles;
    private final MatchResultRepository matchResults;
    private final ReviewSnapshotRepository reviewSnapshots;
    private final ReviewEffectiveMappingResolver mappingResolver;
    private final PurchaseOrderSnapshotReader purchaseOrderSnapshots;

    public ReviewCurrentnessService(
            InvoiceCaseRepository invoiceCases,
            EvidenceBundleRepository evidenceBundles,
            MatchResultRepository matchResults,
            ReviewSnapshotRepository reviewSnapshots,
            ReviewEffectiveMappingResolver mappingResolver,
            PurchaseOrderSnapshotReader purchaseOrderSnapshots) {
        this.invoiceCases = invoiceCases;
        this.evidenceBundles = evidenceBundles;
        this.matchResults = matchResults;
        this.reviewSnapshots = reviewSnapshots;
        this.mappingResolver = mappingResolver;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
    }

    public ReviewFreshness evaluate(UUID caseId, UUID reviewSnapshotId) {
        InvoiceCase invoiceCase =
                invoiceCases.findById(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
        ReviewSnapshot snapshot = loadSnapshot(caseId, reviewSnapshotId);
        return evaluate(invoiceCase, snapshot);
    }

    /**
     * Evaluates freshness against an already-loaded, locked case. Used by write
     * commands that hold the case row lock so the comparison observes exactly
     * the state they will mutate. The current purchasing snapshot is read
     * through its own consistent read transaction.
     */
    public ReviewFreshness evaluate(InvoiceCase invoiceCase, ReviewSnapshot snapshot) {
        Optional<CurrentPurchaseOrderSnapshot> currentPurchasing = purchaseOrderSnapshots
                .findCurrentSnapshot(PurchaseOrderId.of(invoiceCase.purchaseOrder().value()));
        return evaluate(
                invoiceCase,
                snapshot,
                currentPurchasing.map(current -> current.aggregate().snapshotVersion()).orElse(null),
                currentPurchasing.map(CurrentPurchaseOrderSnapshot::payloadHash).orElse(null),
                currentPurchasing);
    }

    /**
     * Evaluates freshness against an already-loaded, locked case and an
     * explicitly supplied current purchasing version/hash. This is the seam
     * approval uses after it has atomically applied the externally prepared
     * snapshot inside its own transaction: a {@code REQUIRES_NEW} read would not
     * observe the uncommitted apply and could wrongly pass, so the caller passes
     * the version/hash it just wrote.
     */
    public ReviewFreshness evaluate(
            InvoiceCase invoiceCase,
            ReviewSnapshot snapshot,
            Long currentPurchasingVersion,
            String currentPurchasingHash) {
        return evaluate(invoiceCase, snapshot, currentPurchasingVersion, currentPurchasingHash, Optional.empty());
    }

    private ReviewFreshness evaluate(
            InvoiceCase invoiceCase,
            ReviewSnapshot snapshot,
            Long currentPurchasingVersion,
            String currentPurchasingHash,
            Optional<CurrentPurchaseOrderSnapshot> currentPurchasing) {
        UUID caseId = invoiceCase.id().value();
        List<StaleReason> reasons = new ArrayList<>();

        if (invoiceCase.status() != InvoiceCaseStatus.REVIEW_PENDING) {
            reasons.add(StaleReason.CASE_STATE);
        }
        if (invoiceCase.version() != snapshot.targetCaseVersion()) {
            reasons.add(StaleReason.CASE_VERSION);
        }

        Optional<EvidenceBundle> latestBundle =
                evidenceBundles.findFirstByInvoiceCaseIdOrderByVersionNumberDesc(caseId);
        if (latestBundle.isEmpty()
                || !latestBundle.get().id().equals(snapshot.evidenceBundleId())) {
            reasons.add(StaleReason.EVIDENCE_BUNDLE);
        }

        Optional<MatchResult> latestResult =
                matchResults.findFirstByInvoiceCaseIdAndEvidenceBundleIdOrderByResultNumberDesc(
                        caseId, snapshot.evidenceBundleId());
        if (latestResult.isEmpty()
                || !latestResult.get().id().equals(snapshot.matchResultId())) {
            reasons.add(StaleReason.MATCH_RESULT);
        }

        int currentWatermark = mappingResolver
                .resolve(caseId, snapshot.evidenceBundleId())
                .watermark();
        if (currentWatermark != snapshot.mappingWatermark()) {
            reasons.add(StaleReason.MAPPING);
        }

        boolean purchasingCurrent = currentPurchasingVersion != null
                && currentPurchasingHash != null
                && currentPurchasingVersion == snapshot.purchasingSnapshotVersion()
                && currentPurchasingHash.equals(snapshot.purchasingSnapshotHash());
        if (!purchasingCurrent) {
            reasons.add(StaleReason.PURCHASING_SNAPSHOT);
        }

        Optional<ReviewSnapshot> latestSnapshot =
                reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId);
        if (latestSnapshot.isEmpty()
                || latestSnapshot.get().snapshotNumber() != snapshot.snapshotNumber()) {
            reasons.add(StaleReason.SUPERSEDED);
        }

        return new ReviewFreshness(
                caseId,
                snapshot.id(),
                snapshot.snapshotNumber(),
                reasons.isEmpty(),
                reasons,
                snapshot.targetCaseVersion(),
                invoiceCase.version(),
                invoiceCase.status().name(),
                snapshot.evidenceBundleId(),
                latestBundle.map(EvidenceBundle::id).orElse(null),
                latestBundle.map(EvidenceBundle::versionNumber).orElse(null),
                snapshot.matchResultId(),
                latestResult.map(MatchResult::id).orElse(null),
                latestResult.map(MatchResult::resultNumber).orElse(null),
                snapshot.mappingWatermark(),
                currentWatermark,
                snapshot.purchasingSnapshotVersion(),
                currentPurchasing.map(current -> current.aggregate().snapshotVersion()).orElse(null),
                snapshot.purchasingSnapshotHash(),
                currentPurchasing.map(CurrentPurchaseOrderSnapshot::payloadHash).orElse(null));
    }

    /**
     * Requires the target to be current, throwing a 409 with the explicit stale
     * reasons otherwise. No side effect is applied.
     */
    public ReviewFreshness requireCurrent(InvoiceCase invoiceCase, ReviewSnapshot snapshot) {
        ReviewFreshness freshness = evaluate(invoiceCase, snapshot);
        if (!freshness.current()) {
            throw new com.invoicematch.core.review.domain.StaleReviewTargetException(
                    invoiceCase.id().value(),
                    snapshot.id(),
                    freshness.reasons(),
                    freshness.currentCaseVersion(),
                    freshness.currentCaseStatus());
        }
        return freshness;
    }

    /**
     * Requires the target to be current against an explicitly supplied current
     * purchasing version/hash (the value approval just applied in its own
     * transaction). Throws a 409 with explicit stale reasons otherwise.
     */
    public ReviewFreshness requireCurrent(
            InvoiceCase invoiceCase,
            ReviewSnapshot snapshot,
            long currentPurchasingVersion,
            String currentPurchasingHash) {
        ReviewFreshness freshness =
                evaluate(invoiceCase, snapshot, currentPurchasingVersion, currentPurchasingHash);
        if (!freshness.current()) {
            throw new com.invoicematch.core.review.domain.StaleReviewTargetException(
                    invoiceCase.id().value(),
                    snapshot.id(),
                    freshness.reasons(),
                    freshness.currentCaseVersion(),
                    freshness.currentCaseStatus());
        }
        return freshness;
    }

    public ReviewSnapshot loadSnapshot(UUID caseId, UUID reviewSnapshotId) {
        ReviewSnapshot snapshot = reviewSnapshots
                .findById(reviewSnapshotId)
                .orElseThrow(() -> new ReviewSnapshotNotFoundException(caseId, reviewSnapshotId));
        if (!snapshot.invoiceCaseId().equals(caseId)) {
            throw new ReviewSnapshotNotFoundException(caseId, reviewSnapshotId);
        }
        return snapshot;
    }
}
