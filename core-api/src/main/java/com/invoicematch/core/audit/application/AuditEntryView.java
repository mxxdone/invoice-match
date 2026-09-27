package com.invoicematch.core.audit.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of one audit entry. {@code actorRoles} is the canonical role name
 * list stored for the actor at the time of the action.
 */
public record AuditEntryView(
        UUID id,
        UUID invoiceCaseId,
        Instant occurredAt,
        String actor,
        List<String> actorRoles,
        String action,
        String targetType,
        String targetId,
        long businessVersion,
        Object before,
        Object after,
        String requestId,
        String traceId) {
}
