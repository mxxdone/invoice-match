package com.invoicematch.core.audit.domain;

/**
 * The audited business actions of the existing Phase 1 write surface. Only
 * actions that are actually implemented in P1-06 are emitted; {@code APPROVE} is
 * reserved for P1-07 and is never fabricated here.
 */
public enum AuditAction {
    CASE_CREATED,
    DRAFT_LINES_REPLACED,
    CASE_SUBMITTED,
    SUPPLEMENT_REVISION_OPENED,
    MATCH_RUN,
    REVIEW_SNAPSHOT_FROZEN,
    ITEM_MAPPED,
    SUPPLEMENT_REQUESTED,
    CASE_REJECTED,
    /** Reserved for the P1-07 approval transaction; not emitted by P1-06. */
    APPROVE
}
