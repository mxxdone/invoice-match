package com.invoicematch.core.review.application;

import com.invoicematch.core.matching.application.AppliedMapping;
import com.invoicematch.core.matching.application.EffectiveMappingResolver;
import com.invoicematch.core.review.domain.ReviewDecision;
import com.invoicematch.core.review.domain.ReviewDecisionType;
import com.invoicematch.core.review.persistence.ReviewDecisionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the effective case-local item mappings for one exact evidence
 * bundle. Mappings are append-only, so the effective decision for an invoice
 * line is the highest-numbered MAPPING decision recorded against that bundle
 * and line. Because resolution is keyed by bundle id, a newly frozen bundle
 * never silently inherits a mapping from an older bundle.
 *
 * <p>The watermark is the highest mapping decision number for the bundle (zero
 * when none). A match result records it so a review snapshot can prove it was
 * computed with the mappings that are still current.
 */
@Component
public class ReviewEffectiveMappingResolver implements EffectiveMappingResolver {

    private final ReviewDecisionRepository decisions;

    public ReviewEffectiveMappingResolver(ReviewDecisionRepository decisions) {
        this.decisions = decisions;
    }

    @Override
    @Transactional(readOnly = true)
    public EffectiveMappings resolve(UUID caseId, UUID evidenceBundleId) {
        List<ReviewDecision> mappingDecisions = decisions.findMappingDecisionsForBundle(
                caseId, evidenceBundleId, ReviewDecisionType.MAPPING);

        Map<Integer, ReviewDecision> latestDecisionByLine = new LinkedHashMap<>();
        int watermark = 0;
        for (ReviewDecision decision : mappingDecisions) {
            latestDecisionByLine.put(decision.mappingLineNumber(), decision);
            watermark = Math.max(watermark, decision.decisionNumber());
        }

        List<AppliedMapping> mappings = latestDecisionByLine.values().stream()
                .map(decision -> new AppliedMapping(
                        decision.mappingLineNumber(),
                        decision.mappingItemId(),
                        decision.mappingPoLineId()))
                .toList();
        return new EffectiveMappings(mappings, watermark);
    }
}
