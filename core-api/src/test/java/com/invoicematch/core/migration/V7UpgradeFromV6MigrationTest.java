package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Proves V7 upgrades a database that already holds V1-V6 rows: existing audit
 * rows survive the audit-action constraint change, the new allocation/payment
 * tables and their guards are created, and {@code APPROVE} becomes a legal audit
 * action. A dedicated database keeps the extra tables from leaking into other
 * migration tests' schema lookups.
 */
class V7UpgradeFromV6MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p1_07_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v7PreservesV6DataAndAddsApprovalSchema() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "6").migrate();

            JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
            UUID caseId = UUID.randomUUID();
            upgradeJdbc.update(
                    "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                            + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                            + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'submitter', 'REVIEW_PENDING',"
                            + " 0, now(), now())",
                    caseId);
            upgradeJdbc.update(
                    "insert into audit_entry (id, invoice_case_id, occurred_at, actor, actor_roles, action,"
                            + " target_type, target_id, business_version, request_id, trace_id)"
                            + " values (?, ?, now(), 'submitter', 'SUBMITTER', 'CASE_CREATED', 'CASE', ?, 0,"
                            + " 'legacy-req', 'trc-legacy')",
                    UUID.randomUUID(),
                    caseId,
                    caseId.toString());
            UUID draftId = UUID.randomUUID();
            upgradeJdbc.update(
                    "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at)"
                            + " values (?, ?, 1, 'SEALED', now(), now())",
                    draftId,
                    caseId);
            UUID bundleId = UUID.randomUUID();
            upgradeJdbc.update(
                    "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                            + " payload_hash, payload, submitted_at)"
                            + " values (?, ?, ?, 1, 'bundle-hash', '{\"lines\":[]}'::jsonb, now())",
                    bundleId,
                    caseId,
                    draftId);
            UUID matchResultId = UUID.randomUUID();
            upgradeJdbc.update(
                    "insert into match_result (id, invoice_case_id, evidence_bundle_id, result_number, result_hash,"
                            + " purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark, payload,"
                            + " created_at)"
                            + " values (?, ?, ?, 1, 'result-hash', 0, 'purchasing-hash', 0, '{}'::jsonb, now())",
                    matchResultId,
                    caseId,
                    bundleId);
            UUID snapshotId = UUID.randomUUID();
            upgradeJdbc.update(
                    "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                            + " match_result_number, snapshot_number, target_case_version,"
                            + " target_evidence_bundle_version, purchasing_snapshot_version,"
                            + " purchasing_snapshot_hash, mapping_watermark, payload_hash, payload, created_at)"
                            + " values (?, ?, ?, ?, 1, 1, 0, 1, 0, 'purchasing-hash', 0, 'snap-hash',"
                            + " '{}'::jsonb, now())",
                    snapshotId,
                    caseId,
                    bundleId,
                    matchResultId);
            // Legacy APPROVED and REJECTED decisions from a V6 database, before the
            // approved-money columns existed.
            upgradeJdbc.update(
                    "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number,"
                            + " decision, decided_by, payload_hash, decided_at)"
                            + " values (?, ?, ?, 1, 'APPROVED', 'reviewer', 'snap-hash', now())",
                    UUID.randomUUID(),
                    caseId,
                    snapshotId);
            upgradeJdbc.update(
                    "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number,"
                            + " decision, decided_by, payload_hash, decided_at)"
                            + " values (?, ?, ?, 2, 'REJECTED', 'reviewer', 'snap-hash', now())",
                    UUID.randomUUID(),
                    caseId,
                    snapshotId);

            flyway(upgradeDataSource, "7").migrate();

            // Existing audit and legacy decisions survived the NOT VALID constraint
            // additions; they are preserved even though they carry no approved money.
            Integer preserved = upgradeJdbc.queryForObject(
                    "select count(*) from audit_entry where invoice_case_id = ? and action = 'CASE_CREATED'",
                    Integer.class,
                    caseId);
            assertThat(preserved).isEqualTo(1);
            Integer legacyApproved = upgradeJdbc.queryForObject(
                    "select count(*) from review_decision where invoice_case_id = ? and decision = 'APPROVED'"
                            + " and approved_amount is null",
                    Integer.class,
                    caseId);
            assertThat(legacyApproved).isEqualTo(1);
            Integer legacyRejected = upgradeJdbc.queryForObject(
                    "select count(*) from review_decision where invoice_case_id = ? and decision = 'REJECTED'",
                    Integer.class,
                    caseId);
            assertThat(legacyRejected).isEqualTo(1);

            // New rows are still enforced: an APPROVED without approved money and
            // before/after versions is rejected, a non-APPROVED decision is allowed.
            assertThatThrownBy(() -> upgradeJdbc.update(
                            "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number,"
                                    + " decision, decided_by, payload_hash, decided_at)"
                                    + " values (?, ?, ?, 3, 'APPROVED', 'reviewer', 'snap-hash', now())",
                            UUID.randomUUID(),
                            caseId,
                            snapshotId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            upgradeJdbc.update(
                    "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number,"
                            + " decision, decided_by, payload_hash, decided_at)"
                            + " values (?, ?, ?, 3, 'SUPPLEMENT_REQUESTED', 'reviewer', 'snap-hash', now())",
                    UUID.randomUUID(),
                    caseId,
                    snapshotId);

            // APPROVE became a legal audit action and the V7 trigger replaced the
            // V6 validation without touching V6.
            String actionCheck = upgradeJdbc.queryForObject(
                    "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_audit_entry_action'",
                    String.class);
            assertThat(actionCheck).contains("APPROVE");

            for (String table : new String[] {"receipt_allocation", "payment_request"}) {
                Integer tables = upgradeJdbc.queryForObject(
                        "select count(*) from information_schema.tables where table_name = ?",
                        Integer.class,
                        table);
                assertThat(tables).as(table + " exists").isEqualTo(1);
            }

            for (String trigger : new String[] {
                    "trg_receipt_allocation_immutable",
                    "trg_receipt_allocation_insert",
                    "trg_receipt_line_allocation_guard",
                    "trg_payment_request_protect"}) {
                Integer found = upgradeJdbc.queryForObject(
                        "select count(*) from pg_trigger where tgname = ? and tgenabled <> 'D'",
                        Integer.class,
                        trigger);
                assertThat(found).as(trigger + " exists").isEqualTo(1);
            }

            Integer applied = upgradeJdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '7' and success",
                    Integer.class);
            assertThat(applied).isEqualTo(1);
        } finally {
            dropDatabase(baseUrl, username, password);
        }
    }

    private Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static String withDatabase(String url, String database) {
        int query = url.indexOf('?');
        String base = query >= 0 ? url.substring(0, query) : url;
        int slash = base.lastIndexOf('/');
        return base.substring(0, slash + 1) + database;
    }

    private void recreateDatabase(String baseUrl, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + UPGRADE_DATABASE + " with (force)");
            statement.execute("create database " + UPGRADE_DATABASE);
        }
    }

    private void dropDatabase(String baseUrl, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + UPGRADE_DATABASE + " with (force)");
        }
    }
}
