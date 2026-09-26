package com.invoicematch.core.audit.domain;

/**
 * The business object an audit entry targets. The case and target integrity
 * check in the schema uses {@link #CASE} to require that a case target id is the
 * owning case id.
 */
public enum AuditTargetType {
    CASE,
    DRAFT_REVISION,
    EVIDENCE_BUNDLE,
    MATCH_RESULT,
    REVIEW_SNAPSHOT,
    REVIEW_DECISION
}
