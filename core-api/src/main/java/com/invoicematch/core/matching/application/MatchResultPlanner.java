package com.invoicematch.core.matching.application;

import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;
import com.invoicematch.core.invoicecase.application.MatchCaseSnapshot;
import com.invoicematch.core.purchasingreference.application.CurrentPurchaseOrderSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Pure deterministic match computation. It resolves the effective mappings,
 * runs the {@link MatchEngine} and returns a {@link PlannedMatch}; it holds no
 * {@code MatchResultRepository} and therefore cannot persist anything.
 *
 * <p>Persistence is restricted, structurally, to the two authorized owners: the
 * OPERATOR {@link MatchingService#run} and the review package-private internal
 * re-match collaborator invoked only by {@code ReviewService.recordMapping}.
 */
@Component
public class MatchResultPlanner {

    private final InvoiceCaseQueryService invoiceCaseQueries;
    private final MatchEngine engine;
    private final EffectiveMappingResolver mappingResolver;
    private final EvidenceBundlePayloadHasher bundleHasher;

    public MatchResultPlanner(
            InvoiceCaseQueryService invoiceCaseQueries,
            MatchEngine engine,
            ObjectProvider<EffectiveMappingResolver> mappingResolvers,
            EvidenceBundlePayloadHasher bundleHasher) {
        this.invoiceCaseQueries = invoiceCaseQueries;
        this.engine = engine;
        this.mappingResolver = mappingResolvers.getIfAvailable(() -> EffectiveMappingResolver.EMPTY);
        this.bundleHasher = bundleHasher;
    }

    public PlannedMatch plan(MatchCaseSnapshot caseSnapshot, CurrentPurchaseOrderSnapshot purchasing) {
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
        return new PlannedMatch(
                computation.resultHash(),
                computation.canonicalJson(),
                purchasing.aggregate().snapshotVersion(),
                purchasing.payloadHash(),
                effective.watermark());
    }
}
