package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the P1-01 Flyway baseline applies to a real PostgreSQL instance and
 * that the foundational constraints and optimistic version column exist where
 * the domain relies on them.
 */
class FlywayPostgresMigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliesBaselineMigrationToRealPostgreSql() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tables)
                .contains(
                        "invoice_case",
                        "draft_revision",
                        "invoice_line",
                        "evidence_bundle",
                        "match_result",
                        "review_snapshot",
                        "review_decision");

        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void invoiceCaseHasOptimisticLockVersionColumn() {
        Map<String, Object> column = jdbc.queryForMap(
                "select data_type, is_nullable from information_schema.columns "
                        + "where table_name = 'invoice_case' and column_name = 'version'");

        assertThat(column).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        UUID caseId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        insertCase(caseId, "DRAFT");
        insertOpenDraft(caseId, draftId, 1);

        assertThatThrownBy(() -> insertLine(caseId, draftId, 1, 0, 1000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeUnitPrice() {
        UUID caseId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        insertCase(caseId, "DRAFT");
        insertOpenDraft(caseId, draftId, 1);

        assertThatThrownBy(() -> insertLine(caseId, draftId, 1, 1, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPhaseTwoAnalysisStatus() {
        assertThatThrownBy(() -> insertCase(UUID.randomUUID(), "ANALYZING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateEvidenceBundleVersion() {
        UUID caseId = UUID.randomUUID();
        insertCase(caseId, "SUBMITTED");
        insertBundle(caseId, UUID.randomUUID(), 1);

        assertThatThrownBy(() -> insertBundle(caseId, UUID.randomUUID(), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOnlyOneOpenDraftRevisionPerCase() {
        UUID caseId = UUID.randomUUID();
        insertCase(caseId, "DRAFT");
        insertOpenDraft(caseId, UUID.randomUUID(), 1);

        assertThatThrownBy(() -> insertOpenDraft(caseId, UUID.randomUUID(), 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void evidenceBundleRowsAreAppendOnly() {
        UUID caseId = UUID.randomUUID();
        UUID bundleId = UUID.randomUUID();
        insertCase(caseId, "SUBMITTED");
        insertBundle(caseId, bundleId, 1);

        assertThatThrownBy(() ->
                        jdbc.update("update evidence_bundle set payload_hash = 'tampered' where id = ?", bundleId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void reviewDecisionRowsAreAppendOnly() {
        UUID caseId = UUID.randomUUID();
        UUID bundleId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        insertCase(caseId, "REVIEW_PENDING");
        insertBundle(caseId, bundleId, 1);
        insertSnapshot(caseId, bundleId, snapshotId);
        insertDecision(caseId, snapshotId, decisionId);

        assertThatThrownBy(() -> jdbc.update("update review_decision set reason = 'tampered' where id = ?", decisionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private void insertCase(UUID id, String status) {
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number, "
                        + "normalized_invoice_number, status, version, created_at, updated_at) "
                        + "values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', ?, 0, now(), now())",
                id,
                status);
    }

    private void insertOpenDraft(UUID caseId, UUID draftId, int revisionNumber) {
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at) "
                        + "values (?, ?, ?, 'OPEN', now())",
                draftId,
                caseId,
                revisionNumber);
    }

    private void insertLine(UUID caseId, UUID draftId, int lineNumber, int quantity, long unitPrice) {
        jdbc.update(
                "insert into invoice_line (id, invoice_case_id, draft_revision_id, line_number, raw_item_name, "
                        + "quantity, unit_price, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'Item', ?, ?, now(), now())",
                UUID.randomUUID(),
                caseId,
                draftId,
                lineNumber,
                quantity,
                unitPrice);
    }

    private void insertBundle(UUID caseId, UUID bundleId, int versionNumber) {
        jdbc.update(
                "insert into evidence_bundle (id, invoice_case_id, version_number, payload_hash, payload, submitted_at) "
                        + "values (?, ?, ?, ?, '{}'::jsonb, now())",
                bundleId,
                caseId,
                versionNumber,
                "hash-" + versionNumber);
    }

    private void insertSnapshot(UUID caseId, UUID bundleId, UUID snapshotId) {
        jdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id, "
                        + "target_case_version, target_evidence_bundle_version, payload_hash, payload, created_at) "
                        + "values (?, ?, ?, null, 0, 1, 'snap-hash', '{}'::jsonb, now())",
                snapshotId,
                caseId,
                bundleId);
    }

    private void insertDecision(UUID caseId, UUID snapshotId, UUID decisionId) {
        jdbc.update(
                "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision, decided_by, "
                        + "payload_hash, decided_at) values (?, ?, ?, 'APPROVED', 'approver-1', 'snap-hash', now())",
                decisionId,
                caseId,
                snapshotId);
    }
}
