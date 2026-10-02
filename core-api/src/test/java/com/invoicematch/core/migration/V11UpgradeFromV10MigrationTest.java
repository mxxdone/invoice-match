package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Proves V11 upgrades a database that already holds P2-01 document registrations
 * and legacy submissions: every registered document gets a reference to its
 * original revision, existing evidence bundles stay on the unchanged legacy
 * schema/payload/hash, and the new guards reject a legacy downgrade of a
 * document-bearing revision.
 */
class V11UpgradeFromV10MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p2_02_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v11BackfillsDocumentReferencesAndPreservesLegacyBundles() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "10").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(upgradeDataSource);

            UUID caseId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            UUID documentId = UUID.randomUUID();
            UUID bundleId = UUID.randomUUID();
            String legacyPayloadHash = "legacy-payload-hash";
            String legacyPayload = "{\"caseId\":\"" + caseId + "\",\"revisionNumber\":1,\"lines\":[]}";

            jdbc.update("insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                    + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                    + " values (?, 'SUP-1', 'PO-1001', 'INV-1', 'INV1', 'submitter', 'DRAFT', 0, now(), now())",
                    caseId);
            jdbc.update("insert into draft_revision (id, invoice_case_id, revision_number, status, created_at)"
                    + " values (?, ?, 1, 'OPEN', now())", revisionId, caseId);
            jdbc.update("update invoice_case set current_draft_revision_id = ? where id = ?", revisionId, caseId);
            jdbc.update("insert into document_upload (id, invoice_case_id, draft_revision_id, file_name, media_type,"
                    + " size_bytes, checksum, upload_key, expires_at, created_at)"
                    + " values (?, ?, ?, 'a.pdf', 'application/pdf', 4, ?, ?, now() + interval '10 minutes', now())",
                    documentId, caseId, revisionId, "a".repeat(64), "uploads/legacy/" + documentId);
            jdbc.update("insert into document (id, object_key, registered_case_version, registered_at)"
                    + " values (?, ?, 1, now())", documentId, "originals/legacy/" + documentId);
            jdbc.update("update draft_revision set status = 'SEALED', sealed_at = now() where id = ?", revisionId);
            jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                    + " payload_hash, payload, submitted_at) values (?, ?, ?, 1, ?, cast(? as jsonb), now())",
                    bundleId, caseId, revisionId, legacyPayloadHash, legacyPayload);

            flyway(upgradeDataSource, "11").migrate();

            // Backfilled reference to the original revision.
            Map<String, Object> reference = jdbc.queryForMap(
                    "select draft_revision_id, document_id, invoice_case_id from draft_revision_document");
            assertThat(reference)
                    .containsEntry("draft_revision_id", revisionId)
                    .containsEntry("document_id", documentId)
                    .containsEntry("invoice_case_id", caseId);

            // The pre-existing bundle stays legacy and byte-identical.
            Map<String, Object> bundle = jdbc.queryForMap(
                    "select payload_schema, payload_hash, payload::text as payload from evidence_bundle where id = ?",
                    bundleId);
            assertThat(bundle).containsEntry("payload_schema", "legacy-v1")
                    .containsEntry("payload_hash", legacyPayloadHash);
            assertThat((String) bundle.get("payload")).contains("\"revisionNumber\": 1");

            // Guards and constraints exist after the upgrade.
            Integer referenceGuard = jdbc.queryForObject(
                    "select count(*) from pg_trigger where tgname = 'trg_draft_revision_document_open'"
                            + " and tgenabled <> 'D'",
                    Integer.class);
            Integer bundleGuard = jdbc.queryForObject(
                    "select count(*) from pg_trigger where tgname = 'trg_evidence_bundle_payload_schema'"
                            + " and tgenabled <> 'D'",
                    Integer.class);
            assertThat(referenceGuard).isEqualTo(1);
            assertThat(bundleGuard).isEqualTo(1);

            // A legacy downgrade of the now document-bearing revision is rejected.
            assertThatThrownBy(() -> jdbc.update(
                            "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                                    + " payload_schema, payload_hash, payload, submitted_at)"
                                    + " values (?, ?, ?, 2, 'legacy-v1', 'h', '{}'::jsonb, now())",
                            UUID.randomUUID(), caseId, revisionId))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("document-v2");

            Integer applied = jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '11' and success", Integer.class);
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
