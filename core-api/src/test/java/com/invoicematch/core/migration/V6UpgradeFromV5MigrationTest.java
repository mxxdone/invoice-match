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
 * Proves V6 upgrades a database that already holds V1-V5 rows: the new
 * mandatory {@code invoice_case.submitted_by} is backfilled with the explicit
 * {@code legacy} sentinel and the audit table is created append-only. The
 * dedicated database keeps the extra table from leaking into other migration
 * tests' schema lookups.
 */
class V6UpgradeFromV5MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p1_06_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v6BackfillsSubmittedByAndCreatesAppendOnlyAudit() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "5").migrate();

            JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
            UUID caseId = UUID.randomUUID();
            upgradeJdbc.update(
                    "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                            + " normalized_invoice_number, status, version, created_at, updated_at)"
                            + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'REVIEW_PENDING', 3, now(), now())",
                    caseId);

            flyway(upgradeDataSource, "6").migrate();

            Map<String, Object> row = upgradeJdbc.queryForMap(
                    "select submitted_by from invoice_case where id = ?", caseId);
            assertThat(row).containsEntry("submitted_by", "__reserved__");

            Integer auditTable = upgradeJdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_name = 'audit_entry'",
                    Integer.class);
            assertThat(auditTable).isEqualTo(1);

            Integer enabledTrigger = upgradeJdbc.queryForObject(
                    "select count(*) from pg_trigger where tgname = 'trg_audit_entry_immutable' and tgenabled <> 'D'",
                    Integer.class);
            assertThat(enabledTrigger).isEqualTo(1);

            Integer applied = upgradeJdbc.queryForObject(
                    "select count(*) from flyway_schema_history where version = '6' and success",
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
