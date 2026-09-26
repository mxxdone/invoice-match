package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class InvoiceCaseManualSubmissionMigrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
    }

    @Test
    void idempotencyTableHasUniqueOperationKey() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'idempotency_record'",
                String.class);
        assertThat(columns)
                .contains("scope", "resource_key", "request_id", "request_hash", "response_status", "response_body");

        String constraint = jdbc.queryForObject(
                "select constraint_name from information_schema.table_constraints"
                        + " where table_name = 'idempotency_record' and constraint_type = 'UNIQUE'",
                String.class);
        assertThat(constraint).isEqualTo("ux_idempotency_request");
    }

    @Test
    void duplicateOperationKeyIsRejected() {
        insertCompleteIdempotency("req-1");
        assertThatThrownBy(() -> insertCompleteIdempotency("req-1")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void incompleteIdempotencyRecordIsRejectedAtCommit() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into idempotency_record (id, scope, resource_key, request_id, request_hash, created_at)"
                                + " values (?, 'scope', 'resource', 'req-incomplete', 'hash', ?)",
                        UUID.randomUUID(),
                        Timestamp.from(T0)))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record", Integer.class)).isZero();
    }

    @Test
    void openRevisionLinesRemainEditable() {
        UUID caseId = insertCase();
        UUID revisionId = insertRevision(caseId, 1, "OPEN", null);
        UUID lineId = insertLine(caseId, revisionId, 1);

        jdbc.update("update invoice_line set quantity = 5 where id = ?", lineId);

        assertThat(jdbc.queryForObject("select quantity from invoice_line where id = ?", Integer.class, lineId))
                .isEqualTo(5);
    }

    @Test
    void sealedRevisionLinesRejectUpdateDeleteAndInsert() {
        UUID caseId = insertCase();
        UUID revisionId = insertRevision(caseId, 1, "OPEN", null);
        UUID lineId = insertLine(caseId, revisionId, 1);
        sealRevision(revisionId);

        assertThatThrownBy(() -> jdbc.update("update invoice_line set quantity = 5 where id = ?", lineId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from invoice_line where id = ?", lineId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into invoice_line (id, invoice_case_id, draft_revision_id, line_number, raw_item_name,"
                                + " quantity, unit_price, confirmed_item_id, created_at, updated_at)"
                                + " values (?, ?, ?, 2, 'Extra', 1, 100, null, ?, ?)",
                        UUID.randomUUID(),
                        caseId,
                        revisionId,
                        Timestamp.from(T0),
                        Timestamp.from(T0)))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("select quantity from invoice_line where id = ?", Integer.class, lineId))
                .isEqualTo(1);
    }

    @Test
    void lineCannotMoveOutOfSealedRevision() {
        UUID caseId = insertCase();
        UUID sealedRevision = insertRevision(caseId, 1, "OPEN", null);
        UUID lineId = insertLine(caseId, sealedRevision, 1);
        sealRevision(sealedRevision);
        UUID openRevision = insertRevision(caseId, 2, "OPEN", null);

        assertThatThrownBy(() -> jdbc.update(
                        "update invoice_line set draft_revision_id = ? where id = ?", openRevision, lineId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                        "select draft_revision_id from invoice_line where id = ?", UUID.class, lineId))
                .isEqualTo(sealedRevision);
    }

    @Test
    void lineCannotMoveIntoSealedRevision() {
        UUID caseId = insertCase();
        UUID sealedRevision = insertRevision(caseId, 1, "OPEN", null);
        sealRevision(sealedRevision);
        UUID openRevision = insertRevision(caseId, 2, "OPEN", null);
        UUID lineId = insertLine(caseId, openRevision, 1);

        assertThatThrownBy(() -> jdbc.update(
                        "update invoice_line set draft_revision_id = ? where id = ?", sealedRevision, lineId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                        "select draft_revision_id from invoice_line where id = ?", UUID.class, lineId))
                .isEqualTo(openRevision);
    }

    @Test
    void lineCannotMoveBetweenSealedRevisions() {
        UUID caseId = insertCase();
        UUID firstSealed = insertRevision(caseId, 1, "OPEN", null);
        UUID lineId = insertLine(caseId, firstSealed, 1);
        sealRevision(firstSealed);
        UUID secondSealed = insertRevision(caseId, 2, "OPEN", null);
        sealRevision(secondSealed);

        assertThatThrownBy(() -> jdbc.update(
                        "update invoice_line set draft_revision_id = ? where id = ?", secondSealed, lineId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void lineMutationLocksTheDraftRevisionAndOrderMovesAscending() {
        String lockFunction = jdbc.queryForObject(
                "select pg_get_functiondef('lock_draft_revision_for_line_change'::regproc)", String.class);
        assertThat(lockFunction).containsIgnoringCase("for update");

        String lineTrigger = jdbc.queryForObject(
                "select pg_get_functiondef('reject_sealed_revision_line_change'::regproc)", String.class);
        assertThat(lineTrigger)
                .contains("lock_draft_revision_for_line_change(NEW.draft_revision_id)")
                .contains("lock_draft_revision_for_line_change(OLD.draft_revision_id)")
                .contains("OLD.draft_revision_id < NEW.draft_revision_id");
    }

    @Test
    void openDraftRevisionCanBeSealedExactlyOnce() {
        UUID caseId = insertCase();
        UUID revisionId = insertRevision(caseId, 1, "OPEN", null);

        sealRevision(revisionId);

        assertThat(jdbc.queryForObject("select status from draft_revision where id = ?", String.class, revisionId))
                .isEqualTo("SEALED");
        assertThatThrownBy(() -> sealRevision(revisionId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void sealedDraftRevisionRejectsEveryUpdateAndDelete() {
        UUID caseId = insertCase();
        UUID otherCaseId = insertCase();
        UUID revisionId = insertRevision(caseId, 1, "OPEN", null);
        sealRevision(revisionId);

        assertThatThrownBy(() -> jdbc.update(
                        "update draft_revision set revision_number = 2 where id = ?", revisionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update draft_revision set sealed_at = ? where id = ?",
                        Timestamp.from(T0.plusSeconds(2)),
                        revisionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update draft_revision set invoice_case_id = ? where id = ?", otherCaseId, revisionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update draft_revision set status = 'OPEN', sealed_at = NULL where id = ?", revisionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from draft_revision where id = ?", revisionId))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("select status from draft_revision where id = ?", String.class, revisionId))
                .isEqualTo("SEALED");
    }

    private UUID insertCase() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'legacy', 'DRAFT', 0, ?, ?)",
                id,
                Timestamp.from(T0),
                Timestamp.from(T0));
        return id;
    }

    private UUID insertRevision(UUID caseId, int number, String status, Instant sealedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at)"
                        + " values (?, ?, ?, ?, ?, ?)",
                id,
                caseId,
                number,
                status,
                Timestamp.from(T0),
                sealedAt == null ? null : Timestamp.from(sealedAt));
        return id;
    }

    private void sealRevision(UUID revisionId) {
        jdbc.update(
                "update draft_revision set status = 'SEALED', sealed_at = ? where id = ?",
                Timestamp.from(T0.plusSeconds(1)),
                revisionId);
    }

    private UUID insertLine(UUID caseId, UUID revisionId, int lineNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_line (id, invoice_case_id, draft_revision_id, line_number, raw_item_name,"
                        + " quantity, unit_price, confirmed_item_id, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 'A4 Paper', 1, 2500, null, ?, ?)",
                id,
                caseId,
                revisionId,
                lineNumber,
                Timestamp.from(T0),
                Timestamp.from(T0));
        return id;
    }

    private void insertCompleteIdempotency(String requestId) {
        jdbc.update(
                "insert into idempotency_record (id, scope, resource_key, request_id, request_hash,"
                        + " response_status, response_body, created_at)"
                        + " values (?, 'scope', 'resource', ?, 'hash', 200, '{}', ?)",
                UUID.randomUUID(),
                requestId,
                Timestamp.from(T0));
    }
}
