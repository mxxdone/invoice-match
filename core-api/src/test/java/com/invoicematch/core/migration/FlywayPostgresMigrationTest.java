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
 * that the foundational constraints, cross-case referential integrity,
 * optimistic version column and append-only protection exist where the domain
 * relies on them.
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
        UUID caseId = seedCase("DRAFT");
        UUID draftId = seedOpenDraft(caseId, 1);

        assertThatThrownBy(() -> insertLine(caseId, draftId, 1, 0, 1000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeUnitPrice() {
        UUID caseId = seedCase("DRAFT");
        UUID draftId = seedOpenDraft(caseId, 1);

        assertThatThrownBy(() -> insertLine(caseId, draftId, 1, 1, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPhaseTwoAnalysisStatus() {
        assertThatThrownBy(() -> seedCase("ANALYZING")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateEvidenceBundleVersion() {
        UUID caseId = seedCase("SUBMITTED");
        UUID draftId = seedSealedDraft(caseId, 1);
        seedBundle(caseId, draftId, 1);

        assertThatThrownBy(() -> seedBundle(caseId, draftId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOnlyOneOpenDraftRevisionPerCase() {
        UUID caseId = seedCase("DRAFT");
        seedOpenDraft(caseId, 1);

        assertThatThrownBy(() -> seedOpenDraft(caseId, 2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsCurrentDraftRevisionOfSameCase() {
        UUID caseId = seedCase("DRAFT");
        UUID draftId = seedOpenDraft(caseId, 1);

        jdbc.update("update invoice_case set current_draft_revision_id = ? where id = ?", draftId, caseId);

        UUID current = jdbc.queryForObject(
                "select current_draft_revision_id from invoice_case where id = ?", UUID.class, caseId);
        assertThat(current).isEqualTo(draftId);
    }

    @Test
    void rejectsCurrentDraftRevisionFromAnotherCase() {
        UUID caseId = seedCase("DRAFT");
        UUID otherCaseId = seedCase("DRAFT");
        UUID draftOfOtherCase = seedOpenDraft(otherCaseId, 1);

        assertThatThrownBy(() -> jdbc.update(
                        "update invoice_case set current_draft_revision_id = ? where id = ?",
                        draftOfOtherCase,
                        caseId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvoiceLineDraftFromAnotherCase() {
        UUID caseId = seedCase("DRAFT");
        UUID otherCaseId = seedCase("DRAFT");
        UUID draftOfOtherCase = seedOpenDraft(otherCaseId, 1);

        assertThatThrownBy(() -> insertLine(caseId, draftOfOtherCase, 1, 1, 1000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEvidenceBundleDraftFromAnotherCase() {
        UUID caseId = seedCase("SUBMITTED");
        UUID otherCaseId = seedCase("DRAFT");
        UUID sealedDraftOfOtherCase = seedSealedDraft(otherCaseId, 1);

        assertThatThrownBy(() -> seedBundle(caseId, sealedDraftOfOtherCase, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEvidenceBundleFromSameCaseOpenDraft() {
        UUID caseId = seedCase("SUBMITTED");
        UUID openDraftId = seedOpenDraft(caseId, 1);

        assertThatThrownBy(() -> seedBundle(caseId, openDraftId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReopeningReferencedSealedDraft() {
        UUID caseId = seedCase("SUBMITTED");
        UUID sealedDraftId = seedSealedDraft(caseId, 1);
        seedBundle(caseId, sealedDraftId, 1);

        assertThatThrownBy(() -> jdbc.update(
                        "update draft_revision set status = 'OPEN', sealed_at = null where id = ?", sealedDraftId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMatchResultBundleFromAnotherCase() {
        UUID caseId = seedCase("SUBMITTED");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID otherCaseId = seedCase("SUBMITTED");

        assertThatThrownBy(() -> insertMatchResult(otherCaseId, bundleId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReviewSnapshotBundleFromAnotherCase() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID otherCaseId = seedCase("SUBMITTED");
        UUID bundleOfOtherCase = seedBundle(otherCaseId, seedSealedDraft(otherCaseId, 1), 1);

        assertThatThrownBy(() -> insertSnapshot(caseId, bundleOfOtherCase, null, 1, "hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReviewSnapshotWrongBundleVersion() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);

        assertThatThrownBy(() -> insertSnapshot(caseId, bundleId, null, 2, "hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReviewSnapshotMatchResultFromAnotherCase() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID otherCaseId = seedCase("REVIEW_PENDING");
        UUID otherBundleId = seedBundle(otherCaseId, seedSealedDraft(otherCaseId, 1), 1);
        UUID matchResultOfOtherCase = insertMatchResult(otherCaseId, otherBundleId);

        assertThatThrownBy(() -> insertSnapshot(caseId, bundleId, matchResultOfOtherCase, 1, "hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReviewSnapshotMatchResultFromDifferentBundleVersion() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID bundleV1 = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID bundleV2 = seedBundle(caseId, seedSealedDraft(caseId, 2), 2);
        UUID matchResultOnV1 = insertMatchResult(caseId, bundleV1);

        assertThatThrownBy(() -> insertSnapshot(caseId, bundleV2, matchResultOnV1, 2, "hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReviewDecisionSnapshotFromAnotherCase() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID snapshotId = insertSnapshot(caseId, bundleId, null, 1, "snap-hash");
        UUID otherCaseId = seedCase("REVIEW_PENDING");

        assertThatThrownBy(() -> insertDecision(otherCaseId, snapshotId, "snap-hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReviewDecisionHashMismatch() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID snapshotId = insertSnapshot(caseId, bundleId, null, 1, "snap-hash");

        assertThatThrownBy(() -> insertDecision(caseId, snapshotId, "other-hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void evidenceBundleRowsAreAppendOnly() {
        UUID caseId = seedCase("SUBMITTED");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);

        assertThatRejected(
                () -> jdbc.update("update evidence_bundle set payload_hash = 'tampered' where id = ?", bundleId));
        assertThatRejected(() -> jdbc.update("delete from evidence_bundle where id = ?", bundleId));
    }

    @Test
    void matchResultRowsAreAppendOnly() {
        UUID caseId = seedCase("SUBMITTED");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID matchResultId = insertMatchResult(caseId, bundleId);

        assertThatRejected(() -> jdbc.update("delete from match_result where id = ?", matchResultId));
    }

    @Test
    void reviewSnapshotRowsAreAppendOnly() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID snapshotId = insertSnapshot(caseId, bundleId, null, 1, "snap-hash");

        assertThatRejected(() -> jdbc.update("delete from review_snapshot where id = ?", snapshotId));
    }

    @Test
    void reviewDecisionRowsAreAppendOnly() {
        UUID caseId = seedCase("REVIEW_PENDING");
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID snapshotId = insertSnapshot(caseId, bundleId, null, 1, "snap-hash");
        UUID decisionId = insertDecision(caseId, snapshotId, "snap-hash");

        assertThatRejected(() -> jdbc.update("update review_decision set reason = 'tampered' where id = ?", decisionId));
        assertThatRejected(() -> jdbc.update("delete from review_decision where id = ?", decisionId));
    }

    private static void assertThatRejected(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private UUID seedCase(String status) {
        UUID caseId = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number, "
                        + "normalized_invoice_number, submitted_by, status, version, created_at, updated_at) "
                        + "values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'legacy', ?, 0, now(), now())",
                caseId,
                status);
        return caseId;
    }

    private UUID seedOpenDraft(UUID caseId, int revisionNumber) {
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at) "
                        + "values (?, ?, ?, 'OPEN', now())",
                draftId,
                caseId,
                revisionNumber);
        return draftId;
    }

    private UUID seedSealedDraft(UUID caseId, int revisionNumber) {
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at) "
                        + "values (?, ?, ?, 'SEALED', now(), now())",
                draftId,
                caseId,
                revisionNumber);
        return draftId;
    }

    private UUID seedBundle(UUID caseId, UUID draftRevisionId, int versionNumber) {
        UUID bundleId = UUID.randomUUID();
        jdbc.update(
                "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number, "
                        + "payload_hash, payload, submitted_at) values (?, ?, ?, ?, ?, '{}'::jsonb, now())",
                bundleId,
                caseId,
                draftRevisionId,
                versionNumber,
                "hash-" + versionNumber);
        return bundleId;
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

    private UUID insertMatchResult(UUID caseId, UUID evidenceBundleId) {
        UUID matchResultId = UUID.randomUUID();
        jdbc.update(
                "insert into match_result (id, invoice_case_id, evidence_bundle_id, result_number, result_hash,"
                        + " purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark,"
                        + " payload, created_at) values (?, ?, ?, 1, ?, 5, 'purchasing-hash', 0,"
                        + " '{}'::jsonb, now())",
                matchResultId,
                caseId,
                evidenceBundleId,
                "result-hash");
        return matchResultId;
    }

    private UUID insertSnapshot(
            UUID caseId, UUID evidenceBundleId, UUID matchResultId, int targetBundleVersion, String payloadHash) {
        UUID snapshotId = UUID.randomUUID();
        Integer matchResultNumber = matchResultId == null ? null : 1;
        jdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                        + " match_result_number, snapshot_number, target_case_version,"
                        + " target_evidence_bundle_version, purchasing_snapshot_version, purchasing_snapshot_hash,"
                        + " mapping_watermark, payload_hash, payload, created_at) "
                        + "values (?, ?, ?, ?, ?, 1, 0, ?, 5, 'purchasing-hash', 0, ?, '{}'::jsonb, now())",
                snapshotId,
                caseId,
                evidenceBundleId,
                matchResultId,
                matchResultNumber,
                targetBundleVersion,
                payloadHash);
        return snapshotId;
    }

    private UUID insertDecision(UUID caseId, UUID snapshotId, String payloadHash) {
        UUID decisionId = UUID.randomUUID();
        jdbc.update(
                "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number, decision,"
                        + " decided_by, payload_hash, decided_at) "
                        + "values (?, ?, ?, 1, 'APPROVED', 'approver-1', ?, now())",
                decisionId,
                caseId,
                snapshotId,
                payloadHash);
        return decisionId;
    }
}
