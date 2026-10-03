package com.invoicematch.core.audit.domain;

/**
 * The audited business actions of the existing Phase 1 write surface. Only
 * actions that are actually implemented are emitted; approval is added by the
 * ticket that implements it (with its own migration), never reserved here.
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
    APPROVE,
    DOCUMENT_UPLOAD_RESERVED,
    DOCUMENT_REGISTERED,
    ANALYSIS_RETRY_RESERVED
}
