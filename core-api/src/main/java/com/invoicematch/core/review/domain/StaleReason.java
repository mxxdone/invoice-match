package com.invoicematch.core.review.domain;

/**
 * A machine-readable reason why a review snapshot or match result is no longer
 * a valid approval/decision target. P1-07 approval calls the same currentness
 * contract, so each reason is reported explicitly rather than collapsed into a
 * single flag.
 */
public enum StaleReason {

    /** The case is no longer in the state that allows a human review decision. */
    CASE_STATE,

    /** The case optimistic version differs from the version the snapshot targeted. */
    CASE_VERSION,

    /** A newer (or different) evidence bundle is now the latest frozen version. */
    EVIDENCE_BUNDLE,

    /** A newer match result exists for the target bundle, or the source result is missing. */
    MATCH_RESULT,

    /** Effective case-local mappings changed after the target was computed. */
    MAPPING,

    /** The current purchasing snapshot version or payload hash changed. */
    PURCHASING_SNAPSHOT,

    /** A newer review snapshot exists for the case, so the target is superseded. */
    SUPERSEDED
}
