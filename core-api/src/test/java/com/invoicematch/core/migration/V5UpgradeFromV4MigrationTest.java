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
 * Proves V5 upgrades a database that already holds V1-V4 rows, including rows
 * in the append-only {@code match_result}, {@code review_snapshot} and
 * {@code review_decision} tables whose backfill UPDATEs would otherwise be
 * rejected by the V1 immutability triggers.
 *
 * <p>It uses a dedicated disposable database so the extra tables and constraint
 * names never leak into the other migration tests' {@code information_schema}
 * and {@code pg_indexes} lookups. The test migrates to V4, inserts
 * representative pre-P1-05 rows in the V1-V4 shape, then migrates to V5 and
 * asserts the captured source facts were backfilled consistently.
 */
class V5UpgradeFromV4MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p1_05_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v5UpgradesADatabaseThatAlreadyHasV4Rows() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "4").migrate();

            JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
            Seed seed = seedV1ToV4Rows(upgradeJdbc);

            flyway(upgradeDataSource, "5").migrate();

            Map<String, Object> matchResult = upgradeJdbc.queryForMap(
                    "select purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark"
                            + " from match_result where id = ?",
                    seed.matchResultId);
            assertThat(matchResult)
                    .containsEntry("purchasing_snapshot_version", 7L)
                    .containsEntry("purchasing_snapshot_hash", "ph-7")
                    .containsEntry("mapping_watermark", 0);

            Map<String, Object> snapshot = upgradeJdbc.queryForMap(
                    "select snapshot_number, match_result_number, purchasing_snapshot_version,"
                            + " purchasing_snapshot_hash, mapping_watermark"
                            + " from review_snapshot where id = ?",
                    seed.snapshotId);
            assertThat(snapshot)
                    .containsEntry("snapshot_number", 1)
                    .containsEntry("match_result_number", 1)
                    .containsEntry("purchasing_snapshot_version", 7L)
                    .containsEntry("purchasing_snapshot_hash", "ph-7")
                    .containsEntry("mapping_watermark", 0);

            Integer decisionNumber = upgradeJdbc.queryForObject(
                    "select decision_number from review_decision where id = ?",
                    Integer.class,
                    seed.decisionId);
            assertThat(decisionNumber).isEqualTo(1);

            Integer applied = upgradeJdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '5' and success",
                    Integer.class);
            assertThat(applied).isEqualTo(1);

            Integer sourceForeignKey = upgradeJdbc.queryForObject(
                    "select count(*) from pg_constraint where conname = 'fk_review_snapshot_source'",
                    Integer.class);
            assertThat(sourceForeignKey).isEqualTo(1);

            // The immutability triggers must be re-enabled after the backfill.
            Integer disabledTriggers = upgradeJdbc.queryForObject(
                    "select count(*) from pg_trigger where tgenabled = 'D'",
                    Integer.class);
            assertThat(disabledTriggers).isZero();
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

    private Seed seedV1ToV4Rows(JdbcTemplate upgradeJdbc) {
        UUID caseId = UUID.randomUUID();
        upgradeJdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'REVIEW_PENDING', 3, now(), now())",
                caseId);
        UUID draftId = UUID.randomUUID();
        upgradeJdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status,"
                        + " created_at, sealed_at) values (?, ?, 1, 'SEALED', now(), now())",
                draftId,
                caseId);
        UUID bundleId = UUID.randomUUID();
        upgradeJdbc.update(
                "insert into evidence_bundle (id, invoice_case_id, draft_revision_id,"
                        + " version_number, payload_hash, payload, submitted_at)"
                        + " values (?, ?, ?, 1, 'bundle-hash', '{\"lines\":[]}'::jsonb, now())",
                bundleId,
                caseId,
                draftId);
        UUID matchResultId = UUID.randomUUID();
        upgradeJdbc.update(
                "insert into match_result (id, invoice_case_id, evidence_bundle_id, result_number,"
                        + " result_hash, payload, created_at) values (?, ?, ?, 1, 'result-hash',"
                        + " '{\"purchasingSnapshot\":{\"snapshotVersion\":7,\"payloadHash\":\"ph-7\"}}'::jsonb, now())",
                matchResultId,
                caseId,
                bundleId);
        UUID snapshotId = UUID.randomUUID();
        upgradeJdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id,"
                        + " match_result_id, target_case_version, target_evidence_bundle_version,"
                        + " payload_hash, payload, created_at)"
                        + " values (?, ?, ?, ?, 3, 1, 'snap-hash', '{}'::jsonb, now())",
                snapshotId,
                caseId,
                bundleId,
                matchResultId);
        UUID decisionId = UUID.randomUUID();
        upgradeJdbc.update(
                "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision,"
                        + " decided_by, payload_hash, decided_at)"
                        + " values (?, ?, ?, 'APPROVED', 'approver-1', 'snap-hash', now())",
                decisionId,
                caseId,
                snapshotId);
        return new Seed(matchResultId, snapshotId, decisionId);
    }

    private record Seed(UUID matchResultId, UUID snapshotId, UUID decisionId) {
    }
}
