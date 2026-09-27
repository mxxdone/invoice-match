package com.invoicematch.core.audit.application;

import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.security.Actor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Input to {@link AuditRecorder}. {@code before} and {@code after} are small,
 * structured summaries (maps/records) serialized to JSON; they must contain no
 * credentials or raw documents.
 */
public record AuditEvent(
        UUID invoiceCaseId,
        Actor actor,
        AuditAction action,
        AuditTargetType targetType,
        String targetId,
        long businessVersion,
        Object before,
        Object after,
        String requestId,
        Instant occurredAt,
        String traceId) {

    /**
     * Convenience form for callers that do not pre-resolve a trace id; the
     * recorder falls back to the current request trace or a generated id.
     */
    public AuditEvent(
            UUID invoiceCaseId,
            Actor actor,
            AuditAction action,
            AuditTargetType targetType,
            String targetId,
            long businessVersion,
            Object before,
            Object after,
            String requestId,
            Instant occurredAt) {
        this(invoiceCaseId, actor, action, targetType, targetId, businessVersion, before, after, requestId, occurredAt,
                null);
    }

    public AuditEvent {
        Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
