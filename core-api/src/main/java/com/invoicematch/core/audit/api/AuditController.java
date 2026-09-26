package com.invoicematch.core.audit.api;

import com.invoicematch.core.audit.application.AuditHistoryPage;
import com.invoicematch.core.audit.application.AuditQueryService;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cursor-based audit history for one case. Read-only and restricted to the
 * financial reviewer and operator roles; the case is still confirmed to exist
 * so a missing case is 404 rather than an empty page.
 */
@RestController
@RequestMapping("/api/invoice-cases")
public class AuditController {

    private final AuthorizationService authorization;
    private final AuditQueryService auditQueries;

    public AuditController(AuthorizationService authorization, AuditQueryService auditQueries) {
        this.authorization = authorization;
        this.auditQueries = auditQueries;
    }

    @GetMapping("/{id}/audit-entries")
    public AuditHistoryPage history(
            @PathVariable UUID id,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        authorization.requireCaseExists(id);
        return auditQueries.history(id, cursor, limit);
    }
}
