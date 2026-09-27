package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

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

            flyway(upgradeDataSource, "7").migrate();

            // Existing audit row and its action survived the constraint change.
            Integer preserved = upgradeJdbc.queryForObject(
                    "select count(*) from audit_entry where invoice_case_id = ? and action = 'CASE_CREATED'",
                    Integer.class,
                    caseId);
            assertThat(preserved).isEqualTo(1);

            // APPROVE is now a legal audit action.
            upgradeJdbc.update(
                    "insert into audit_entry (id, invoice_case_id, occurred_at, actor, actor_roles, action,"
                            + " target_type, target_id, business_version, request_id, trace_id)"
                            + " values (?, ?, now(), 'approver', 'APPROVER', 'APPROVE', 'CASE', ?, 0,"
                            + " 'approve-req', 'trc-approve')",
                    UUID.randomUUID(),
                    caseId,
                    caseId.toString());

            for (String table : new String[] {"receipt_allocation", "payment_request"}) {
                Integer tables = upgradeJdbc.queryForObject(
                        "select count(*) from information_schema.tables where table_name = ?",
                        Integer.class,
                        table);
                assertThat(tables).as(table + " exists").isEqualTo(1);
            }

            Integer allocationImmutable = upgradeJdbc.queryForObject(
                    "select count(*) from pg_trigger where tgname = 'trg_receipt_allocation_immutable'"
                            + " and tgenabled <> 'D'",
                    Integer.class);
            assertThat(allocationImmutable).isEqualTo(1);
            Integer allocationGuard = upgradeJdbc.queryForObject(
                    "select count(*) from pg_trigger where tgname = 'trg_receipt_allocation_balance'"
                            + " and tgenabled <> 'D'",
                    Integer.class);
            assertThat(allocationGuard).isEqualTo(1);

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
