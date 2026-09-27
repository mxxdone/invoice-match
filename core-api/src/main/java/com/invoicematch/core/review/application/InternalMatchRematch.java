package com.invoicematch.core.review.application;

import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;
import com.invoicematch.core.invoicecase.application.MatchCaseSnapshot;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.MatchResultPlanner;
import com.invoicematch.core.matching.application.PlannedMatch;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.matching.persistence.MatchResultRepository;
import com.invoicematch.core.purchasingreference.application.CurrentPurchaseOrderSnapshot;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Package-private internal deterministic re-match for the review mapping flow.
 *
 * <p>It is deliberately not public and lives only in {@code review.application},
 * so no other Spring component can reference the type and append a match result
 * outside a mapping decision. It runs inside {@code ReviewService.recordMapping}'s
 * already-authorized (APPROVER), case-locked, idempotent transaction; it takes
 * the case row lock again (same transaction, no new lock order) and revalidates
 * the current purchasing snapshot before appending. The mapping's atomic
 * {@code ITEM_MAPPED} audit covers the resulting match result, so this does not
 * emit a standalone operator-style {@code MATCH_RUN}.
 */
@Component
class InternalMatchRematch {

    private final InvoiceCaseQueryService invoiceCaseQueries;
    private final MatchingService matching;
    private final MatchResultPlanner planner;
    private final MatchResultRepository matchResults;
    private final Clock clock;

    InternalMatchRematch(
            InvoiceCaseQueryService invoiceCaseQueries,
            MatchingService matching,
            MatchResultPlanner planner,
            MatchResultRepository matchResults,
            Clock clock) {
        this.invoiceCaseQueries = invoiceCaseQueries;
        this.matching = matching;
        this.planner = planner;
        this.matchResults = matchResults;
        this.clock = clock;
    }

    MatchResult append(UUID caseId) {
        MatchCaseSnapshot caseSnapshot = invoiceCaseQueries.loadForMatching(caseId);
        CurrentPurchaseOrderSnapshot purchasing = matching.requireCurrentPurchasingSnapshot(caseSnapshot);
        PlannedMatch planned = planner.plan(caseSnapshot, purchasing);
        int resultNumber = matchResults.maxResultNumber(caseId) + 1;
        return matchResults.saveAndFlush(MatchResult.record(
                UUID.randomUUID(),
                caseId,
                caseSnapshot.evidenceBundleId(),
                resultNumber,
                planned.resultHash(),
                planned.purchasingSnapshotVersion(),
                planned.purchasingSnapshotHash(),
                planned.mappingWatermark(),
                planned.canonicalJson(),
                clock.instant()));
    }
}
