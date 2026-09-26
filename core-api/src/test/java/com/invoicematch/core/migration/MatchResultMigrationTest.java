package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P1-04 migration checks: the append-only match_result history, its index and
 * the composite foreign keys that keep a result tied to a bundle of the same
 * case.
 */
class MatchResultMigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table invoice_case cascade");
    }

    @Test
    void matchResultHistoryIndexExists() {
        Integer indexCount = jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'ix_match_result_case_created_at'",
                Integer.class);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void matchResultUpdateAndDeleteAreRejected() {
        UUID caseId = seedCase();
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID matchResultId = insertMatchResult(caseId, bundleId);

        assertThatRejected(
                () -> jdbc.update("update match_result set result_hash = 'tampered' where id = ?", matchResultId));
        assertThatRejected(() -> jdbc.update("delete from match_result where id = ?", matchResultId));
        assertThat(jdbc.queryForObject("select count(*) from match_result", Integer.class)).isEqualTo(1);
    }

    @Test
    void matchResultCannotReferenceAnotherCasesBundle() {
        UUID caseId = seedCase();
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);
        UUID otherCaseId = seedCase();

        assertThatThrownBy(() -> insertMatchResult(otherCaseId, bundleId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repeatedResultsForTheSameBundleAreAppendable() {
        UUID caseId = seedCase();
        UUID bundleId = seedBundle(caseId, seedSealedDraft(caseId, 1), 1);

        insertMatchResult(caseId, bundleId);
        insertMatchResult(caseId, bundleId);

        assertThat(jdbc.queryForObject("select count(*) from match_result", Integer.class)).isEqualTo(2);
    }

    private static void assertThatRejected(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private UUID seedCase() {
        UUID caseId = UUID.randomUUID();
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'REVIEW_PENDING', 0, now(), now())",
                caseId);
        return caseId;
    }

    private UUID seedSealedDraft(UUID caseId, int revisionNumber) {
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at)"
                        + " values (?, ?, ?, 'SEALED', now(), now())",
                draftId,
                caseId,
                revisionNumber);
        return draftId;
    }

    private UUID seedBundle(UUID caseId, UUID draftRevisionId, int versionNumber) {
        UUID bundleId = UUID.randomUUID();
        jdbc.update(
                "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                        + " payload_hash, payload, submitted_at) values (?, ?, ?, ?, ?, '{}'::jsonb, now())",
                bundleId,
                caseId,
                draftRevisionId,
                versionNumber,
                "hash-" + versionNumber);
        return bundleId;
    }

    private UUID insertMatchResult(UUID caseId, UUID evidenceBundleId) {
        UUID matchResultId = UUID.randomUUID();
        jdbc.update(
                "insert into match_result (id, invoice_case_id, evidence_bundle_id, result_hash, payload, created_at)"
                        + " values (?, ?, ?, ?, '{}'::jsonb, now())",
                matchResultId,
                caseId,
                evidenceBundleId,
                "result-hash");
        return matchResultId;
    }
}
