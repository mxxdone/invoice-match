package com.invoicematch.core.review.domain;

/**
 * The kinds of review decision a person can finalise against a specific
 * version: a mapping, a supplement request, a rejection or an approval.
 */
public enum ReviewDecisionType {
    MAPPING,
    SUPPLEMENT_REQUESTED,
    REJECTED,
    APPROVED
}
