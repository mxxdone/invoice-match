package com.invoicematch.core.matching.application;

import java.util.List;
import java.util.UUID;

/**
 * Seam through which deterministic matching applies the current effective
 * case-local mappings without depending on the review package. The review
 * module implements this port; matching defaults to {@link #EMPTY} so a match
 * run with no mappings behaves exactly as P1-04 did.
 *
 * <p>Mappings are scoped to the exact evidence bundle so a new bundle never
 * silently inherits old mappings.
 */
public interface EffectiveMappingResolver {

    EffectiveMappingResolver EMPTY = (caseId, evidenceBundleId) -> EffectiveMappings.NONE;

    EffectiveMappings resolve(UUID caseId, UUID evidenceBundleId);

    /**
     * Effective mappings for one bundle plus the per-bundle mapping watermark
     * (highest mapping decision number applied, or zero). The watermark lets a
     * match result and a review snapshot prove they were computed with the
     * mappings that are still current.
     */
    record EffectiveMappings(List<AppliedMapping> mappings, int watermark) {

        public static final EffectiveMappings NONE = new EffectiveMappings(List.of(), 0);

        public EffectiveMappings {
            mappings = List.copyOf(mappings);
            if (watermark < 0) {
                throw new IllegalArgumentException("watermark must not be negative: " + watermark);
            }
        }
    }
}
