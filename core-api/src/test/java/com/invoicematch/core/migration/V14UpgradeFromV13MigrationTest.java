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
 * Proves V14 upgrades a V13 database with committed reservations without
 * touching the existing run identity/hash/workflow: the execution columns and
 * the immutable document-result table are added, the expanded run state machine
 * and its exact SQLSTATEs are enforced, and results are append-only.
 */
class V14UpgradeFromV13MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p2_07_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v14AddsExecutionLifecycleAndDocumentResultsPreservingReservations() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "13").migrate();
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
                    "select id, invoice_case_id, evidence_bundle_id, input_version, evidence_payload_hash,"
                            + " workflow_version, status, created_at, updated_at from analysis_run where id = ?",
                    runId);

            flyway(upgradeDataSource, "14").migrate();

            Map<String, Object> after = jdbc.queryForMap(
                    "select id, invoice_case_id, evidence_bundle_id, input_version, evidence_payload_hash,"
                            + " workflow_version, status, created_at, updated_at, execution_token, lease_until,"
                            + " execution_attempt from analysis_run where id = ?",
                    runId);
            assertThat(after.get("id")).isEqualTo(before.get("id"));
            assertThat(after.get("invoice_case_id")).isEqualTo(before.get("invoice_case_id"));
            assertThat(after.get("evidence_bundle_id")).isEqualTo(before.get("evidence_bundle_id"));
            assertThat(after.get("input_version")).isEqualTo(before.get("input_version"));
            assertThat(after.get("evidence_payload_hash")).isEqualTo(before.get("evidence_payload_hash"));
            assertThat(after.get("workflow_version")).isEqualTo(before.get("workflow_version"));
            assertThat(after.get("status")).isEqualTo("QUEUED");
            assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
            assertThat(after.get("execution_token")).isNull();
            assertThat(after.get("lease_until")).isNull();
            assertThat(after.get("execution_attempt")).isEqualTo(0);

            assertThat(jdbc.queryForObject(
                            "select count(*) from information_schema.tables"
                                    + " where table_schema = 'public' and table_name = 'analysis_document_result'",
                            Integer.class))
                    .isEqualTo(1);

            // A RUNNING row without a token/lease violates the state/field combo.
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_run set status = 'RUNNING' where id = ?", runId));

            // A valid claim advances by one attempt.
            jdbc.update("update analysis_run"
                            + " set status = 'RUNNING', execution_token = gen_random_uuid(),"
                            + " lease_until = clock_timestamp() + interval '1 minute',"
                            + " execution_attempt = execution_attempt + 1, updated_at = now() where id = ?",
                    runId);
            // A stayed-running update may not lower the attempt...
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_run set execution_attempt = 0 where id = ?", runId));
            // ...and may not skip back to QUEUED.
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_run set status = 'QUEUED', execution_token = null, lease_until = null"
                            + " where id = ?",
                    runId));

            // A heartbeat extends the lease under the same token/attempt.
            for (String mutation : new String[] {
                    "execution_token = gen_random_uuid()",
                    "execution_token = gen_random_uuid(), execution_attempt = execution_attempt + 1",
                    "execution_attempt = execution_attempt + 1",
                    "lease_until = lease_until - interval '1 second'"}) {
                assertDatabaseRejects("23514", () -> jdbc.update(
                        "update analysis_run set " + mutation + " where id = ?", runId));
            }
            jdbc.update("update analysis_run set lease_until = clock_timestamp() + interval '2 minutes'"
                    + " where id = ?", runId);
            assertDatabaseRejects("23514", () -> jdbc.update("update analysis_run"
                    + " set status = 'COMPLETED', execution_token = null, lease_until = null,"
                    + " execution_attempt = execution_attempt + 1 where id = ?", runId));
            assertDatabaseRejects("23514", () -> jdbc.update("update analysis_run"
                    + " set status = 'STALE', execution_token = null, lease_until = null,"
                    + " execution_attempt = execution_attempt + 1 where id = ?", runId));

            UUID expiredRun = UUID.randomUUID();
            UUID expiredBundle = UUID.randomUUID();
            jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                    + " payload_schema, payload_hash, payload, submitted_at)"
                    + " select ?, invoice_case_id, draft_revision_id, 2, payload_schema, payload_hash,"
                    + " payload, submitted_at from evidence_bundle where id = ?", expiredBundle, bundleId);
            jdbc.update("insert into analysis_run (id, invoice_case_id, evidence_bundle_id, input_version,"
                    + " evidence_payload_hash, workflow_version, status, created_at, updated_at)"
                    + " values (?, ?, ?, 2, 'legacy-hash', 'document-parser-v1', 'QUEUED', now(), now())",
                    expiredRun, caseId, expiredBundle);
            jdbc.update("update analysis_run set status = 'RUNNING', execution_token = gen_random_uuid(),"
                    + " lease_until = clock_timestamp() + interval '1 second', execution_attempt = 1"
                    + " where id = ?", expiredRun);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "select lease_until <= clock_timestamp() from analysis_run where id = ?",
                    Boolean.class, expiredRun))) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("lease expiry timeout");
                Thread.sleep(25);
            }
            assertDatabaseRejects("23514", () -> jdbc.update("update analysis_run"
                    + " set lease_until = clock_timestamp() + interval '1 minute' where id = ?", expiredRun));
            assertDatabaseRejects("23514", () -> jdbc.update("update analysis_run"
                    + " set execution_token = gen_random_uuid(), lease_until = clock_timestamp() + interval '1 minute'"
                    + " where id = ?", expiredRun));
            jdbc.update("update analysis_run set execution_token = gen_random_uuid(),"
                    + " execution_attempt = execution_attempt + 1,"
                    + " lease_until = clock_timestamp() + interval '1 minute' where id = ?", expiredRun);
            assertThat(jdbc.queryForObject("select execution_attempt from analysis_run where id = ?",
                    Integer.class, expiredRun)).isEqualTo(2);

            // Completion drops the claim and is terminal.
            jdbc.update("update analysis_run"
                            + " set status = 'COMPLETED', execution_token = null, lease_until = null,"
                            + " updated_at = now() where id = ?",
                    runId);
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "update analysis_run set status = 'RUNNING', execution_token = gen_random_uuid(),"
                            + " lease_until = clock_timestamp() + interval '1 minute' where id = ?",
                    runId));

            // Identity stays immutable and delete stays rejected.
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "update analysis_run set evidence_payload_hash = 'tampered' where id = ?", runId));
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "delete from analysis_run where id = ?", runId));

            // Results are append-only and enforce the outcome/payload combination.
            jdbc.update("insert into analysis_document_result"
                            + " (run_id, document_id, source_checksum, outcome, parser_version,"
                            + " result_schema_version, payload, error_code, payload_hash, created_at)"
                            + " values (?, ?, 'checksum', 'SUCCESS', 'parser', 'document-parse-v1',"
                            + " '{\"k\":1}'::jsonb, null, 'hash', now())",
                    runId, UUID.randomUUID());
            assertDatabaseRejects("23514", () -> jdbc.update(
                    "insert into analysis_document_result"
                            + " (run_id, document_id, source_checksum, outcome, parser_version,"
                            + " result_schema_version, payload, error_code, payload_hash, created_at)"
                            + " values (?, ?, 'checksum', 'FAILURE', 'parser', 'document-parse-v1',"
                            + " '{\"k\":1}'::jsonb, 'PDF_CORRUPT', 'hash', now())",
                    runId, UUID.randomUUID()));
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "update analysis_document_result set payload_hash = 'x' where run_id = ?", runId));
            assertDatabaseRejects("23000", () -> jdbc.update(
                    "delete from analysis_document_result where run_id = ?", runId));

            Integer applied = jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '14' and success", Integer.class);
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
