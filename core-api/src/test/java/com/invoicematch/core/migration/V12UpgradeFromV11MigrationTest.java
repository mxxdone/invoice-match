package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Proves V12 upgrades a database that already holds P2-01..P2-04 submissions:
 * the existing case/bundle rows are untouched, the analysis reservation tables,
 * composite foreign key and append-only guards are added, and a reservation can
 * be created for a pre-existing frozen bundle.
 */
class V12UpgradeFromV11MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p2_05_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v12AddsAnalysisReservationTablesAndPreservesExistingSubmissions() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "11").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(upgradeDataSource);

            UUID caseId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            UUID bundleId = UUID.randomUUID();
            jdbc.update("insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                            + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                            + " values (?, 'SUP-1', 'PO-1001', 'INV-1', 'INV1', 'submitter', 'DRAFT', 0, now(), now())",
                    caseId);
            jdbc.update("insert into draft_revision (id, invoice_case_id, revision_number, status, created_at)"
                            + " values (?, ?, 1, 'OPEN', now())",
                    revisionId, caseId);
            jdbc.update("update invoice_case set current_draft_revision_id = ? where id = ?", revisionId, caseId);
            jdbc.update("update draft_revision set status = 'SEALED', sealed_at = now() where id = ?", revisionId);
            jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                            + " payload_hash, payload, submitted_at)"
                            + " values (?, ?, ?, 1, 'legacy-hash', '{\"revisionNumber\":1}'::jsonb, now())",
                    bundleId, caseId, revisionId);

            // Full pre-migration snapshot of the existing rows: identity,
            // version, times, hash, schema and the actual payload text.
            Map<String, Object> caseBefore = caseSnapshot(jdbc, caseId);
            Map<String, Object> revisionBefore = revisionSnapshot(jdbc, revisionId);
            Map<String, Object> bundleBefore = bundleSnapshot(jdbc, bundleId);
            assertThat(bundleBefore).containsEntry("payload_text", "{\"revisionNumber\": 1}");

            flyway(upgradeDataSource, "12").migrate();

            // New tables and guards exist after the upgrade.
            assertThat(jdbc.queryForObject(
                            "select count(*) from information_schema.tables where table_schema = 'public'"
                                    + " and table_name in ('analysis_run', 'analysis_request_outbox')",
                            Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject(
                            "select count(*) from pg_trigger where tgname in"
                                    + " ('trg_analysis_run_mutation', 'trg_analysis_request_outbox_mutation')"
                                    + " and tgenabled <> 'D'",
                            Integer.class))
                    .isEqualTo(2);

            // The pre-existing case, revision and bundle are preserved snapshot
            // for snapshot: identity, version, times, hash, schema and payload
            // text are all unchanged by the migration.
            assertThat(caseSnapshot(jdbc, caseId)).isEqualTo(caseBefore);
            assertThat(revisionSnapshot(jdbc, revisionId)).isEqualTo(revisionBefore);
            assertThat(bundleSnapshot(jdbc, bundleId)).isEqualTo(bundleBefore);

            // A reservation for the pre-existing frozen bundle is valid.
            UUID runId = UUID.randomUUID();
            jdbc.update("insert into analysis_run (id, invoice_case_id, evidence_bundle_id, input_version,"
                            + " evidence_payload_hash, workflow_version, status, created_at, updated_at)"
                            + " values (?, ?, ?, 1, 'legacy-hash', 'document-parser-v1', 'QUEUED', now(), now())",
                    runId, caseId, bundleId);
            UUID eventId = UUID.randomUUID();
            jdbc.update("insert into analysis_request_outbox"
                            + " (id, analysis_run_id, schema_version, payload, status, created_at)"
                            + " values (?, ?, 'analysis-request-v1', '{}'::jsonb, 'READY', now())",
                    eventId, runId);

            // Appendix-only guards are enforced with their raised SQLSTATE.
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "update analysis_run set evidence_payload_hash = 'tampered' where id = ?", runId));
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "delete from analysis_request_outbox where id = ?", eventId));
            assertDatabaseRejects("23505", () -> jdbc.update(
                    "insert into analysis_request_outbox"
                            + " (id, analysis_run_id, schema_version, payload, status, created_at)"
                            + " values (?, ?, 'analysis-request-v1', '{}'::jsonb, 'READY', now())",
                    UUID.randomUUID(),
                    runId));

            Integer applied = jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '12' and success", Integer.class);
            assertThat(applied).isEqualTo(1);
        } finally {
            dropDatabase(baseUrl, username, password);
        }
    }

    private static Map<String, Object> caseSnapshot(JdbcTemplate jdbc, UUID caseId) {
        return jdbc.queryForMap(
                "select id, supplier_id, purchase_order_id, invoice_number, normalized_invoice_number,"
                        + " submitted_by, status, version, submitted_at, created_at, updated_at,"
                        + " current_draft_revision_id from invoice_case where id = ?",
                caseId);
    }

    private static Map<String, Object> revisionSnapshot(JdbcTemplate jdbc, UUID revisionId) {
        return jdbc.queryForMap(
                "select id, invoice_case_id, revision_number, status, created_at, sealed_at"
                        + " from draft_revision where id = ?",
                revisionId);
    }

    private static Map<String, Object> bundleSnapshot(JdbcTemplate jdbc, UUID bundleId) {
        return jdbc.queryForMap(
                "select id, invoice_case_id, draft_revision_id, draft_revision_status, version_number,"
                        + " payload_schema, payload_hash, payload::text as payload_text, submitted_at"
                        + " from evidence_bundle where id = ?",
                bundleId);
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
