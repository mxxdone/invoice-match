package com.invoicematch.core.audit.application;

import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.security.Role;
import com.invoicematch.core.trace.TraceContext;
import com.invoicematch.core.trace.TraceId;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one audit row in the caller's transaction. Because it joins the
 * business transaction, a failure to record the audit aborts the whole
 * transaction, so a business mutation can never commit without its audit. An
 * idempotent replay returns before the business method runs and therefore
 * records no second audit row.
 */
@Component
public class AuditRecorder {

    private final JdbcTemplate jdbc;
    private final AuditStateSummarizer summarizer;

    public AuditRecorder(JdbcTemplate jdbc, AuditStateSummarizer summarizer) {
        this.jdbc = jdbc;
        this.summarizer = summarizer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        if (event.actor().roles().isEmpty()) {
            throw new IllegalStateException(
                    "Audit requires a role-bearing actor but got '" + event.actor().username() + "'");
        }
        String roles = event.actor().roles().stream()
                .sorted()
                .map(Role::name)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        jdbc.update(
                "insert into audit_entry (id, invoice_case_id, occurred_at, actor, actor_roles, action,"
                        + " target_type, target_id, business_version, before_state, after_state, request_id,"
                        + " trace_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)",
                UUID.randomUUID(),
                event.invoiceCaseId(),
                Timestamp.from(event.occurredAt()),
                event.actor().username(),
                roles,
                event.action().name(),
                event.targetType().name(),
                event.targetId(),
                event.businessVersion(),
                summarizer.summarize(event.before()),
                summarizer.summarize(event.after()),
                event.requestId(),
                resolveTraceId());
    }

    private static String resolveTraceId() {
        String current = TraceContext.current();
        return current != null ? current : TraceId.generate();
    }
}
