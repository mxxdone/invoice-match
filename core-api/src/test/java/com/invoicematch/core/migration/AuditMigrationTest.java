package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1-06 migration checks: the mandatory, immutable authoritative
 * {@code submitted_by}; the principal-namespaced idempotency key; the audit
 * table shape; and the append-only plus semantic guards that reject forged
 * targets, roles and versions as well as UPDATE/DELETE.
 */
class AuditMigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
    }

    @Test
    void auditInsertionLocksTheCaseAgainstAConcurrentVersionChange() throws Exception {
        UUID caseId = seedCase("DRAFT", 1);
        CountDownLatch auditInserted = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> audit = pool.submit(() -> transactions.executeWithoutResult(status -> {
                insertAudit(caseId, "submitter", "SUBMITTER", "CASE", caseId.toString(), 1, "CASE_CREATED");
                auditInserted.countDown();
                await(releaseAudit);
            }));
            assertThat(auditInserted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> update = pool.submit(() -> transactions.executeWithoutResult(status ->
                    jdbc.update("update invoice_case set version = version + 1 where id = ?", caseId)));

            Thread.sleep(500);
            assertThat(update.isDone())
                    .as("a concurrent case version update must wait for the audit's share lock")
                    .isFalse();

            releaseAudit.countDown();
            audit.get(10, TimeUnit.SECONDS);
            update.get(10, TimeUnit.SECONDS);
        } finally {
            releaseAudit.countDown();
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select version from invoice_case where id = ?", Long.class, caseId))
                .isEqualTo(2L);
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
    void submittedByIsImmutableAfterInsert() {
        UUID caseId = seedCase("DRAFT", 0);

        assertThatThrownBy(() -> jdbc.update(
                        "update invoice_case set submitted_by = 'someone-else' where id = ?", caseId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        // Other columns may still change.
        jdbc.update("update invoice_case set status = 'SUBMITTED' where id = ?", caseId);
        assertThat(jdbc.queryForObject(
                        "select status from invoice_case where id = ?", String.class, caseId))
                .isEqualTo("SUBMITTED");
    }

    @Test
    void idempotencyKeyIsNamespacedByActor() {
        insertIdempotency("principal-a", "req-1");
        insertIdempotency("principal-b", "req-1");

        assertThat(jdbc.queryForObject(
                        "select count(*) from idempotency_record where request_id = 'req-1'", Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> insertIdempotency("principal-a", "req-1"))
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
        UUID caseId = seedCase("DRAFT", 1);
        UUID auditId = insertAudit(caseId, "submitter", "SUBMITTER", "CASE", caseId.toString(), 1, "CASE_CREATED");

        assertThatRejected(() -> jdbc.update("update audit_entry set actor = 'tampered' where id = ?", auditId));
        assertThatRejected(() -> jdbc.update("delete from audit_entry where id = ?", auditId));
    }

    @Test
    void auditEntryRejectsBlankActorAndMismatchedCaseTarget() {
        UUID caseId = seedCase("DRAFT", 1);

        assertThatThrownBy(() -> insertAudit(
                        caseId, "   ", "SUBMITTER", "CASE", caseId.toString(), 1, "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "SUBMITTER", "CASE", UUID.randomUUID().toString(), 1, "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEntryRejectsUnknownActionAndReservedApproval() {
        UUID caseId = seedCase("DRAFT", 1);

        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "SUBMITTER", "CASE", caseId.toString(), 1, "NOT_AN_ACTION"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(
                        caseId, "approver", "APPROVER", "CASE", caseId.toString(), 1, "APPROVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEntryRejectsInvalidOrNonCanonicalRoles() {
        UUID caseId = seedCase("DRAFT", 1);

        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "AUDITOR", "CASE", caseId.toString(), 1, "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "APPROVER,APPROVER", "CASE", caseId.toString(), 1, "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "APPROVER,SUBMITTER", "CASE", caseId.toString(), 1, "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "", "CASE", caseId.toString(), 1, "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditEntryRejectsBusinessVersionThatDoesNotMatchTheCase() {
        UUID caseId = seedCase("DRAFT", 2);

        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "SUBMITTER", "CASE", caseId.toString(), 1, "CASE_CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertAudit(caseId, "submitter", "SUBMITTER", "CASE", caseId.toString(), 2, "CASE_CREATED");
    }

    @Test
    void auditEntryRejectsFakeOrCrossCaseTargets() {
        UUID caseId = seedCase("DRAFT", 1);
        UUID draftOfCase = seedSealedDraft(caseId, 1);
        UUID otherCase = seedCase("DRAFT", 1);
        UUID draftOfOtherCase = seedSealedDraft(otherCase, 1);

        // Nonexistent typed target.
        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "SUBMITTER", "DRAFT_REVISION", UUID.randomUUID().toString(), 1,
                        "DRAFT_LINES_REPLACED"))
                .isInstanceOf(DataAccessException.class);
        // A valid draft that belongs to a different case.
        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "SUBMITTER", "DRAFT_REVISION", draftOfOtherCase.toString(), 1,
                        "DRAFT_LINES_REPLACED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A non-uuid target id is rejected rather than trusted.
        assertThatThrownBy(() -> insertAudit(
                        caseId, "submitter", "SUBMITTER", "DRAFT_REVISION", "not-a-uuid", 1,
                        "DRAFT_LINES_REPLACED"))
                .isInstanceOf(DataAccessException.class);

        insertAudit(caseId, "submitter", "SUBMITTER", "DRAFT_REVISION", draftOfCase.toString(), 1,
                "DRAFT_LINES_REPLACED");
    }

    private static void assertThatRejected(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private UUID seedCase(String status, long version) {
        UUID caseId = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'submitter', ?, ?, now(), now())",
                caseId,
                status,
                version);
        return caseId;
    }

    private UUID seedSealedDraft(UUID caseId, int revisionNumber) {
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at)"
                        + " values (?, ?, ?, 'SEALED', now(), now())",
                draftId,
                caseId,
                revisionNumber);
        return draftId;
    }

    private void insertIdempotency(String actor, String requestId) {
        jdbc.update(
                "insert into idempotency_record (id, scope, resource_key, actor, request_id, request_hash,"
                        + " response_status, response_body, created_at)"
                        + " values (?, 's', 'r', ?, ?, 'h', 200, '{}', now())",
                UUID.randomUUID(),
                actor,
                requestId);
    }

    private UUID insertAudit(
            UUID caseId,
            String actor,
            String roles,
            String targetType,
            String targetId,
            long businessVersion,
            String action) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into audit_entry (id, invoice_case_id, occurred_at, actor, actor_roles, action,"
                        + " target_type, target_id, business_version, before_state, after_state, request_id,"
                        + " trace_id) values (?, ?, now(), ?, ?, ?, ?, ?, ?, null,"
                        + " '{\"status\":\"DRAFT\"}'::jsonb, 'req-1', 'trace-1')",
                id,
                caseId,
                actor,
                roles,
                action,
                targetType,
                targetId,
                businessVersion);
        return id;
    }
}
