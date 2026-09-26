package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P1-05 migration checks: the new review ordering/source columns, the
 * normalized mapping fields and their foreign keys/checks, and the append-only
 * protection of snapshots and decisions.
 */
class ReviewWorkflowMigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table invoice_case cascade");
    }

    @Test
    void reviewWorkflowColumnsExist() {
        List<String> snapshotColumns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'review_snapshot'",
                String.class);
        assertThat(snapshotColumns)
                .contains(
                        "snapshot_number",
                        "match_result_number",
                        "purchasing_snapshot_version",
                        "purchasing_snapshot_hash",
                        "mapping_watermark");

        List<String> decisionColumns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'review_decision'",
                String.class);
        assertThat(decisionColumns)
                .contains(
                        "decision_number",
                        "mapping_bundle_id",
                        "mapping_line_number",
                        "mapping_item_id",
                        "mapping_po_line_id");

        List<String> matchColumns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'match_result'",
                String.class);
        assertThat(matchColumns)
                .contains("purchasing_snapshot_version", "purchasing_snapshot_hash", "mapping_watermark");
    }

    @Test
    void snapshotNumberMustBePositiveAndUniquePerCase() {
        Seed seed = seedCaseWithBundle();

        assertThatThrownBy(() -> insertSnapshot(seed, 0, seed.matchResultId, 1, "hash-0"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertSnapshot(seed, 1, seed.matchResultId, 1, "hash-1");
        assertThatThrownBy(() -> insertSnapshot(seed, 1, seed.matchResultId, 1, "hash-1b"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void snapshotMatchResultIdAndNumberMustAgree() {
        Seed seed = seedCaseWithBundle();

        assertThatThrownBy(() -> jdbc.update(
                        "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                                + " match_result_number, snapshot_number, target_case_version,"
                                + " target_evidence_bundle_version, purchasing_snapshot_version,"
                                + " purchasing_snapshot_hash, mapping_watermark, payload_hash, payload, created_at)"
                                + " values (?, ?, ?, ?, null, 1, 0, 1, 5, 'purchasing-hash', 0, 'h',"
                                + " '{}'::jsonb, now())",
                        UUID.randomUUID(),
                        seed.caseId,
                        seed.bundleId,
                        seed.matchResultId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decisionNumberMustBePositiveAndUniquePerCase() {
        Seed seed = seedCaseWithBundle();
        UUID snapshotId = insertSnapshot(seed, 1, seed.matchResultId, 1, "snap-hash");

        assertThatThrownBy(() -> insertDecision(seed, snapshotId, "snap-hash", 0, "APPROVED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertDecision(seed, snapshotId, "snap-hash", 1, "APPROVED");
        assertThatThrownBy(() -> insertDecision(seed, snapshotId, "snap-hash", 1, "REJECTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mappingDecisionRequiresACompleteMappingTarget() {
        Seed seed = seedCaseWithBundle();
        UUID snapshotId = insertSnapshot(seed, 1, seed.matchResultId, 1, "snap-hash");

        assertThatThrownBy(() -> jdbc.update(
                        "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number,"
                                + " decision, decided_by, payload_hash, decided_at) "
                                + "values (?, ?, ?, 1, 'MAPPING', 'reviewer', ?, now())",
                        UUID.randomUUID(),
                        seed.caseId,
                        snapshotId,
                        "snap-hash"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                        "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number,"
                                + " decision, decided_by, payload_hash, decided_at, mapping_bundle_id,"
                                + " mapping_line_number, mapping_item_id, mapping_po_line_id) "
                                + "values (?, ?, ?, 1, 'APPROVED', 'reviewer', ?, now(), ?, 1, 'ITEM-A', 'POL-1')",
                        UUID.randomUUID(),
                        seed.caseId,
                        snapshotId,
                        "snap-hash",
                        seed.bundleId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mappingDecisionBundleMustBelongToTheSameCase() {
        Seed seed = seedCaseWithBundle();
        UUID snapshotId = insertSnapshot(seed, 1, seed.matchResultId, 1, "snap-hash");
        Seed other = seedCaseWithBundle();

        assertThatThrownBy(() -> jdbc.update(
                        "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number,"
                                + " decision, decided_by, payload_hash, decided_at, mapping_bundle_id,"
                                + " mapping_line_number, mapping_item_id, mapping_po_line_id) "
                                + "values (?, ?, ?, 1, 'MAPPING', 'reviewer', ?, now(), ?, 1, 'ITEM-A', 'POL-1')",
                        UUID.randomUUID(),
                        seed.caseId,
                        snapshotId,
                        "snap-hash",
                        other.bundleId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reviewSnapshotAndDecisionAreAppendOnly() {
        Seed seed = seedCaseWithBundle();
        UUID snapshotId = insertSnapshot(seed, 1, seed.matchResultId, 1, "snap-hash");
        UUID decisionId = insertDecision(seed, snapshotId, "snap-hash", 1, "APPROVED");

        assertThatRejected(() -> jdbc.update(
                "update review_snapshot set payload_hash = 'tampered' where id = ?", snapshotId));
        assertThatRejected(() -> jdbc.update("delete from review_snapshot where id = ?", snapshotId));
        assertThatRejected(() -> jdbc.update(
                "update review_decision set reason = 'tampered' where id = ?", decisionId));
        assertThatRejected(() -> jdbc.update("delete from review_decision where id = ?", decisionId));
    }

    private static void assertThatRejected(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private UUID insertSnapshot(Seed seed, int snapshotNumber, UUID matchResultId, int bundleVersion, String hash) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                        + " match_result_number, snapshot_number, target_case_version,"
                        + " target_evidence_bundle_version, purchasing_snapshot_version, purchasing_snapshot_hash,"
                        + " mapping_watermark, payload_hash, payload, created_at) "
                        + "values (?, ?, ?, ?, 1, ?, 0, ?, 5, 'purchasing-hash', 0, ?, '{}'::jsonb, now())",
                snapshotId,
                seed.caseId,
                seed.bundleId,
                matchResultId,
                snapshotNumber,
                bundleVersion,
                hash);
        return snapshotId;
    }

    private UUID insertDecision(
            Seed seed, UUID snapshotId, String snapshotHash, int decisionNumber, String decision) {
        UUID decisionId = UUID.randomUUID();
        jdbc.update(
                "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number, decision,"
                        + " decided_by, payload_hash, decided_at) values (?, ?, ?, ?, ?, 'reviewer', ?, now())",
                decisionId,
                seed.caseId,
                snapshotId,
                decisionNumber,
                decision,
                snapshotHash);
        return decisionId;
    }

    private Seed seedCaseWithBundle() {
        UUID caseId = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'REVIEW_PENDING', 0, now(), now())",
                caseId);
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at)"
                        + " values (?, ?, 1, 'SEALED', now(), now())",
                draftId,
                caseId);
        UUID bundleId = UUID.randomUUID();
        jdbc.update(
                "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                        + " payload_hash, payload, submitted_at)"
                        + " values (?, ?, ?, 1, 'bundle-hash', '{\"lines\":[]}'::jsonb, now())",
                bundleId,
                caseId,
                draftId);
        UUID matchResultId = UUID.randomUUID();
        jdbc.update(
                "insert into match_result (id, invoice_case_id, evidence_bundle_id, result_number, result_hash,"
                        + " purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark,"
                        + " payload, created_at) values (?, ?, ?, 1, 'result-hash', 5, 'purchasing-hash', 0,"
                        + " '{}'::jsonb, now())",
                matchResultId,
                caseId,
                bundleId);
        return new Seed(caseId, bundleId, matchResultId);
    }

    private record Seed(UUID caseId, UUID bundleId, UUID matchResultId) {
    }
}
