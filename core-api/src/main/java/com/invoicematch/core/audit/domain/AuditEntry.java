package com.invoicematch.core.audit.domain;

import com.invoicematch.core.security.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One immutable, append-only audit row. {@code before}/{@code after} are
 * structured change summaries produced by the application, never raw request
 * bodies, credentials or hidden model reasoning.
 */
public record AuditEntry(
        UUID id,
        UUID invoiceCaseId,
        Instant occurredAt,
        String actor,
        List<Role> actorRoles,
        AuditAction action,
        AuditTargetType targetType,
        String targetId,
        long businessVersion,
        String beforeState,
        String afterState,
        String requestId,
        String traceId) {

    public AuditEntry {
        actorRoles = List.copyOf(actorRoles);
    }
}
