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

/** Upgrade preserves historical execution attempts, immutable inputs and results. */
class V15UpgradeFromV14MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String UPGRADE_DATABASE = "p2_09_upgrade";

    @Autowired
    private Environment environment;

    @Test
    void v15PreservesExistingRunsEventsAndResultsAndAddsBoundedRecovery() throws Exception {
        String baseUrl = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        String upgradeUrl = withDatabase(baseUrl, UPGRADE_DATABASE);

        recreateDatabase(baseUrl, username, password);
        DataSource upgradeDataSource = new DriverManagerDataSource(upgradeUrl, username, password);
        try {
            flyway(upgradeDataSource, "14").migrate();
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

            // V14 allowed more than three attempts. Upgrade must preserve this history.
            for (int attempt=1;attempt<=4;attempt++) {
                jdbc.update("update analysis_run set status='RUNNING',execution_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '1 second',execution_attempt=execution_attempt+1 where id=?",runId);
                if (attempt<4) Thread.sleep(1100);
            }
            jdbc.update("insert into analysis_document_result(run_id,document_id,source_checksum,outcome,parser_version,result_schema_version,payload,error_code,payload_hash,created_at)"
                    + " values (?,?,'checksum','FAILURE','parser','document-parse-v1',null,'PDF_CORRUPT','hash',now())",runId,UUID.randomUUID());
            var before=jdbc.queryForMap("select * from analysis_run where id=?",runId);
            var event=jdbc.queryForMap("select *,payload::text as frozen from analysis_request_outbox where id=?",eventId);
            var result=jdbc.queryForMap("select * from analysis_document_result where run_id=?",runId);
            flyway(upgradeDataSource,"15").migrate();
            var after=jdbc.queryForMap("select * from analysis_run where id=?",runId);
            assertThat(after.remove("attempt_limit")).isEqualTo(4);
            assertThat(after).isEqualTo(before);
            assertThat(jdbc.queryForMap("select *,payload::text as frozen from analysis_request_outbox where id=?",eventId)).isEqualTo(event);
            assertThat(jdbc.queryForMap("select * from analysis_document_result where run_id=?",runId)).isEqualTo(result);
            assertDatabaseRejects("23514",()->jdbc.update("update analysis_run set attempt_limit=100 where id=?",runId));
            jdbc.update("update analysis_run set status='DEAD_LETTERED',execution_token=null,lease_until=null where id=?",runId);
            assertDatabaseRejects("23514",()->jdbc.update("update analysis_run set status='QUEUED' where id=?",runId));
            jdbc.update("update analysis_run set status='QUEUED',attempt_limit=execution_attempt+3 where id=?",runId);
            assertThat(jdbc.queryForObject("select execution_attempt from analysis_run where id=?",Integer.class,runId)).isEqualTo(4);
            assertThat(jdbc.queryForObject("select attempt_limit from analysis_run where id=?",Integer.class,runId)).isEqualTo(7);
        } finally {
            dropDatabase(baseUrl,username,password);
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
