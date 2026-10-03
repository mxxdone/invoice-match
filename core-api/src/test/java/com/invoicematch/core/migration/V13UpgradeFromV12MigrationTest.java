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
 * Proves V13 upgrades a V12 database with committed reservations without
 * touching the existing identity/schema/payload/createdAt: the lease columns are
 * added, old READY rows are backfilled due at {@code created_at}, and the
 * expanded state machine plus claim-identity/immutability guards are enforced
 * with their exact SQLSTATEs.
 */
class V13UpgradeFromV12MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p2_06_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v13AddsLeaseLifecycleAndPreservesExistingReservations() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "12").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(upgradeDataSource);

            UUID caseId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            UUID bundleId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
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
            jdbc.update("insert into analysis_run (id, invoice_case_id, evidence_bundle_id, input_version,"
                            + " evidence_payload_hash, workflow_version, status, created_at, updated_at)"
                            + " values (?, ?, ?, 1, 'legacy-hash', 'document-parser-v1', 'QUEUED', now(), now())",
                    runId, caseId, bundleId);
            jdbc.update("insert into analysis_request_outbox"
                            + " (id, analysis_run_id, schema_version, payload, status, created_at)"
                            + " values (?, ?, 'analysis-request-v1', '{\"eventId\":\"legacy\"}'::jsonb, 'READY', now())",
                    eventId, runId);

            Map<String, Object> before = jdbc.queryForMap(
                    "select id, analysis_run_id, schema_version, payload::text as payload_text, status, created_at"
                            + " from analysis_request_outbox where id = ?",
                    eventId);

            flyway(upgradeDataSource, "13").migrate();

            // New columns exist and the old READY row is due at created_at.
            Map<String, Object> after = jdbc.queryForMap(
                    "select id, analysis_run_id, schema_version, payload::text as payload_text, status, created_at,"
                            + " claim_token, lease_until, next_attempt_at, attempt_count, published_at, last_error_code"
                            + " from analysis_request_outbox where id = ?",
                    eventId);
            assertThat(after.get("id")).isEqualTo(before.get("id"));
            assertThat(after.get("analysis_run_id")).isEqualTo(before.get("analysis_run_id"));
            assertThat(after.get("schema_version")).isEqualTo(before.get("schema_version"));
            assertThat(after.get("payload_text")).isEqualTo(before.get("payload_text"));
            assertThat(after.get("status")).isEqualTo("READY");
            assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
            assertThat(after.get("next_attempt_at")).isEqualTo(before.get("created_at"));
            assertThat(after.get("attempt_count")).isEqualTo(0);
            assertThat(after.get("claim_token")).isNull();
            assertThat(after.get("lease_until")).isNull();
            assertThat(after.get("published_at")).isNull();
            assertThat(after.get("last_error_code")).isNull();

            assertThat(jdbc.queryForObject(
                            "select count(*) from pg_indexes where schemaname = 'public'"
                                    + " and indexname = 'ix_analysis_request_outbox_due'",
                            Integer.class))
                    .isEqualTo(1);

            // A CLAIMED row without a token/lease violates the state/field combo.
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_request_outbox set status = 'CLAIMED' where id = ?", eventId));

            // A valid claim can advance to PUBLISHED.
            jdbc.update("update analysis_request_outbox"
                            + " set status = 'CLAIMED', claim_token = gen_random_uuid(),"
                            + " lease_until = clock_timestamp() + interval '1 minute',"
                            + " attempt_count = attempt_count + 1 where id = ?",
                    eventId);
            // A same-state update may not extend the live lease...
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_request_outbox"
                            + " set lease_until = clock_timestamp() + interval '2 minutes' where id = ?",
                    eventId));
            // ...and attempt_count may not decrease.
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_request_outbox set attempt_count = 0 where id = ?", eventId));
            jdbc.update("update analysis_request_outbox"
                            + " set status = 'PUBLISHED', published_at = clock_timestamp(),"
                            + " claim_token = null, lease_until = null where id = ?",
                    eventId);
            // PUBLISHED is terminal.
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_request_outbox set status = 'READY' where id = ?", eventId));

            // Subject/schema/payload immutability and delete protection survive.
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "update analysis_request_outbox set payload = '{}'::jsonb where id = ?", eventId));
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "delete from analysis_request_outbox where id = ?", eventId));

            Integer applied = jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '13' and success", Integer.class);
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
