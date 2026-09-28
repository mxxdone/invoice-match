package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.payment.domain.PaymentExportPayload;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
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
 * Proves V8 upgrades a database that already holds committed V7 approvals: the
 * export lifecycle is expanded, and every pre-existing PaymentRequest gets one
 * deterministic READY outbox event (version 1) with the canonical payload/hash
 * and idempotency key. The backfill and the Java writer share the exact
 * canonical contract.
 */
class V8UpgradeFromV7MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p1_08_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v8ExpandsExportStateAndBackfillsOneEventPerPayment() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "7").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(upgradeDataSource);

            Map<String, Object> first = insertApprovedPayment(jdbc, "1", 150_000L, "PO-1");
            Map<String, Object> second = insertApprovedPayment(jdbc, "2", 90_000L, "PO-1");

            flyway(upgradeDataSource, "8").migrate();

            // PENDING migrated to NOT_SENT for every pre-existing payment.
            Integer legacyPending = jdbc.queryForObject(
                    "select count(*) from payment_request where status = 'PENDING'", Integer.class);
            assertThat(legacyPending).isZero();
            Integer notSent = jdbc.queryForObject(
                    "select count(*) from payment_request where status = 'NOT_SENT'", Integer.class);
            assertThat(notSent).isEqualTo(2);

            // Exactly one deterministic READY event per payment.
            assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isEqualTo(2);
            for (Map<String, Object> payment : java.util.List.of(first, second)) {
                verifyBackfilledEvent(jdbc, payment);
            }

            Integer applied = jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '8' and success", Integer.class);
            assertThat(applied).isEqualTo(1);

            for (String trigger : new String[] {
                    "trg_outbox_event_insert",
                    "trg_outbox_event_guard",
                    "trg_outbox_delivery_attempt_immutable"}) {
                Integer found = jdbc.queryForObject(
                        "select count(*) from pg_trigger where tgname = ? and tgenabled <> 'D'",
                        Integer.class,
                        trigger);
                assertThat(found).as(trigger + " exists").isEqualTo(1);
            }
        } finally {
            dropDatabase(baseUrl, username, password);
        }
    }

    private void verifyBackfilledEvent(JdbcTemplate jdbc, Map<String, Object> payment) {
        UUID paymentId = (UUID) payment.get("paymentId");
        UUID caseId = (UUID) payment.get("caseId");
        UUID snapshotId = (UUID) payment.get("snapshotId");
        String payloadHash = (String) payment.get("payloadHash");
        String externalKey = (String) payment.get("externalKey");
        long amount = (Long) payment.get("amount");
        String purchaseOrderId = (String) payment.get("purchaseOrderId");

        Map<String, Object> event = jdbc.queryForMap(
                "select status, idempotency_key, payload_hash, payload::text as payload, export_version"
                        + " from outbox_event where payment_request_id = ?",
                paymentId);
        assertThat(event.get("status")).isEqualTo("READY");
        assertThat(event.get("export_version")).isEqualTo(1L);
        assertThat(event.get("idempotency_key")).isEqualTo(paymentId + ":1");

        PaymentExportPayload canonical = new PaymentExportPayload(
                paymentId, caseId, purchaseOrderId, snapshotId, payloadHash, externalKey, amount, "KRW", 1L);
        assertThat(event.get("payload_hash")).isEqualTo(PaymentExportPayload.sha256Hex(canonical.canonicalJson()));
        // The stored jsonb is the same object as the canonical Java payload.
        String dbPayloadEquals = jdbc.queryForObject(
                "select (payload = cast(? as jsonb))::text from outbox_event where payment_request_id = ?",
                String.class,
                canonical.canonicalJson(),
                paymentId);
        assertThat(dbPayloadEquals).isEqualTo("true");
    }

    @Test
    void v8BackfillCanonicalizesSpecialCharactersAndUnicode() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);
        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "7").migrate();
            JdbcTemplate jdbc = new JdbcTemplate(upgradeDataSource);
            // Quote, backslash, LF, tab, control character and Unicode, all of
            // which must be escaped identically by Java and PostgreSQL.
            String oddPo = "PO-\"\\\r\n\t\u0001 한글 😀";
            Map<String, Object> payment = insertApprovedPayment(jdbc, "odd", 150_000L, oddPo);

            flyway(upgradeDataSource, "8").migrate();

            verifyBackfilledEvent(jdbc, payment);
        } finally {
            dropDatabase(baseUrl, username, password);
        }
    }

    private Map<String, Object> insertApprovedPayment(
            JdbcTemplate jdbc, String suffix, long amount, String purchaseOrderId) {
        UUID caseId = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', ?, ?, ?, 'submitter', 'EXPORT_PENDING', 0, now(), now())",
                caseId,
                purchaseOrderId,
                "INV-" + suffix,
                "INV" + suffix);
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at)"
                        + " values (?, ?, 1, 'SEALED', now(), now())",
                draftId,
                caseId);
        UUID bundleId = UUID.randomUUID();
        String payloadHash = "snap-hash-" + suffix;
        jdbc.update(
                "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                        + " payload_hash, payload, submitted_at)"
                        + " values (?, ?, ?, 1, ?, '{\"lines\":[]}'::jsonb, now())",
                bundleId,
                caseId,
                draftId,
                "bundle-hash-" + suffix);
        UUID snapshotId = UUID.randomUUID();
        jdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                        + " match_result_number, snapshot_number, target_case_version,"
                        + " target_evidence_bundle_version, purchasing_snapshot_version, purchasing_snapshot_hash,"
                        + " mapping_watermark, payload_hash, payload, created_at)"
                        + " values (?, ?, ?, null, null, 1, 0, 1, 5, 'purchasing-hash', 0, ?, '{}'::jsonb, now())",
                snapshotId,
                caseId,
                bundleId,
                payloadHash);
        UUID decisionId = UUID.randomUUID();
        jdbc.update(
                "insert into review_decision (id, invoice_case_id, review_snapshot_id, decision_number, decision,"
                        + " decided_by, payload_hash, decided_at, approved_amount, approved_currency,"
                        + " approved_case_version_before, approved_case_version_after, approval_actor_roles,"
                        + " approval_request_id, approval_trace_id)"
                        + " values (?, ?, ?, 1, 'APPROVED', 'approver', ?, now(), ?, 'KRW', 0, 1, 'APPROVER',"
                        + " 'req', 'trc')",
                decisionId,
                caseId,
                snapshotId,
                payloadHash,
                amount);
        UUID paymentId = UUID.randomUUID();
        String externalKey = "PAYMENT:" + caseId + ":" + snapshotId;
        jdbc.update(
                "insert into payment_request (id, invoice_case_id, purchase_order_id, review_decision_id,"
                        + " review_snapshot_id, review_payload_hash, evidence_bundle_id, external_request_key,"
                        + " amount, currency, status, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'KRW', 'PENDING', now())",
                paymentId,
                caseId,
                purchaseOrderId,
                decisionId,
                snapshotId,
                payloadHash,
                bundleId,
                externalKey,
                amount);
        Map<String, Object> result = new HashMap<>();
        result.put("caseId", caseId);
        result.put("paymentId", paymentId);
        result.put("snapshotId", snapshotId);
        result.put("payloadHash", payloadHash);
        result.put("externalKey", externalKey);
        result.put("amount", amount);
        result.put("purchaseOrderId", purchaseOrderId);
        return result;
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
