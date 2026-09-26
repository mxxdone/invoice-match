package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P1-06 migration checks: the mandatory authoritative {@code submitted_by},
 * the audit table shape and its declarative integrity, and the append-only
 * trigger that rejects UPDATE and DELETE.
 */
class AuditMigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table invoice_case cascade");
    }

    @Test
    void submittedByIsMandatory() {
        Map<String, Object> column = jdbc.queryForMap(
                "select data_type, is_nullable from information_schema.columns"
                        + " where table_name = 'invoice_case' and column_name = 'submitted_by'");

        assertThat(column).containsEntry("is_nullable", "NO");

        assertThatThrownBy(() -> jdbc.update(
                        "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                                + " normalized_invoice_number, status, version, created_at, updated_at)"
                                + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'DRAFT', 0, now(), now())",
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEntryHasTheExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'audit_entry'", String.class);

        assertThat(columns)
                .contains(
                        "id",
                        "invoice_case_id",
                        "occurred_at",
                        "actor",
                        "actor_roles",
                        "action",
                        "target_type",
                        "target_id",
                        "business_version",
                        "before_state",
                        "after_state",
                        "request_id",
                        "trace_id");
    }

    @Test
    void auditEntryIsAppendOnly() {
        UUID caseId = seedCase();
        UUID auditId = insertAudit(caseId, "submitter", "CASE", caseId.toString(), "CASE_CREATED");

        assertThatRejected(() -> jdbc.update("update audit_entry set actor = 'tampered' where id = ?", auditId));
        assertThatRejected(() -> jdbc.update("delete from audit_entry where id = ?", auditId));
    }

    @Test
    void auditEntryRejectsBlankActorAndMismatchedCaseTarget() {
        UUID caseId = seedCase();

        assertThatThrownBy(() -> insertAudit(caseId, "   ", "CASE", caseId.toString(), "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(caseId, "submitter", "CASE", UUID.randomUUID().toString(), "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEntryRejectsUnknownAction() {
        UUID caseId = seedCase();

        assertThatThrownBy(() -> insertAudit(caseId, "submitter", "CASE", caseId.toString(), "NOT_AN_ACTION"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void assertThatRejected(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private UUID seedCase() {
        UUID caseId = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'submitter', 'DRAFT', 0, now(), now())",
                caseId);
        return caseId;
    }

    private UUID insertAudit(UUID caseId, String actor, String targetType, String targetId, String action) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into audit_entry (id, invoice_case_id, occurred_at, actor, actor_roles, action,"
                        + " target_type, target_id, business_version, before_state, after_state, request_id,"
                        + " trace_id) values (?, ?, now(), ?, 'SUBMITTER', ?, ?, ?, 1, null,"
                        + " '{\"status\":\"DRAFT\"}'::jsonb, 'req-1', 'trace-1')",
                id,
                caseId,
                actor,
                action,
                targetType,
                targetId);
        return id;
    }
}
