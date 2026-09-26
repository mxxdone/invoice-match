package com.invoicematch.core.review.application;

import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.domain.ReviewSnapshotNotFoundException;
import com.invoicematch.core.review.persistence.ReviewDecisionRepository;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the review workflow: immutable snapshot history, one snapshot by
 * its business order, its machine-checkable freshness and the append-only
 * decision history. Old snapshots remain queryable and report exactly why they
 * are stale.
 */
@Service
@Transactional(readOnly = true)
public class ReviewQueryService {

    private final InvoiceCaseQueryService invoiceCaseQueries;
    private final ReviewSnapshotRepository snapshots;
    private final ReviewDecisionRepository decisions;
    private final ReviewCurrentnessService currentness;

    public ReviewQueryService(
            InvoiceCaseQueryService invoiceCaseQueries,
            ReviewSnapshotRepository snapshots,
            ReviewDecisionRepository decisions,
            ReviewCurrentnessService currentness) {
        this.invoiceCaseQueries = invoiceCaseQueries;
        this.snapshots = snapshots;
        this.decisions = decisions;
        this.currentness = currentness;
    }

    public ReviewSnapshotView latest(UUID caseId) {
        invoiceCaseQueries.requireCase(caseId);
        ReviewSnapshot snapshot = snapshots
                .findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId)
                .orElseThrow(() -> new ReviewSnapshotNotFoundException(caseId));
        return ReviewSnapshotView.from(snapshot);
    }

    public List<ReviewSnapshotView> history(UUID caseId) {
        invoiceCaseQueries.requireCase(caseId);
        return snapshots.findByInvoiceCaseIdOrderBySnapshotNumberAsc(caseId).stream()
                .map(ReviewSnapshotView::from)
                .toList();
    }

    public ReviewSnapshotView get(UUID caseId, int snapshotNumber) {
        invoiceCaseQueries.requireCase(caseId);
        return ReviewSnapshotView.from(requireSnapshot(caseId, snapshotNumber));
    }

    public ReviewFreshness freshness(UUID caseId, int snapshotNumber) {
        invoiceCaseQueries.requireCase(caseId);
        ReviewSnapshot snapshot = requireSnapshot(caseId, snapshotNumber);
        return currentness.evaluate(caseId, snapshot.id());
    }

    public List<ReviewDecisionView> decisions(UUID caseId) {
        invoiceCaseQueries.requireCase(caseId);
        return decisions.findByInvoiceCaseIdOrderByDecisionNumberAsc(caseId).stream()
                .map(ReviewDecisionView::from)
                .toList();
    }

    private ReviewSnapshot requireSnapshot(UUID caseId, int snapshotNumber) {
        return snapshots
                .findByInvoiceCaseIdAndSnapshotNumber(caseId, snapshotNumber)
                .orElseThrow(() -> new ReviewSnapshotNotFoundException(caseId));
    }
}
