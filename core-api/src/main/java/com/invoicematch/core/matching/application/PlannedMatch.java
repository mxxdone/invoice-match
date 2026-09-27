package com.invoicematch.core.matching.application;

/**
 * Pure result of planning a deterministic match: the canonical payload/hash and
 * the source facts it was computed from. It intentionally carries no append
 * order or identity, so only the persistence owners assign the per-case result
 * number and id.
 */
public record PlannedMatch(
        String resultHash,
        String canonicalJson,
        long purchasingSnapshotVersion,
        String purchasingSnapshotHash,
        int mappingWatermark) {
}
