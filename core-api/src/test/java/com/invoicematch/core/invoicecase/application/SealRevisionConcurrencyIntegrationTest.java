package com.invoicematch.core.invoicecase.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two-connection PostgreSQL tests for the draft revision lock shared between
 * invoice-line mutations and submission.
 *
 * <p>The guarantee under test is consistency for the normal application submit
 * and for concurrent raw line mutations: the relational lines of the sealed
 * revision must equal the frozen evidence bundle payload. It does not claim that
 * the database validates every semantic of an arbitrary raw JSON payload.
 */
class SealRevisionConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final ControlledLockInterceptor INTERCEPTOR = new ControlledLockInterceptor();

    @TestConfiguration
    static class InterceptorConfiguration {
        @Bean
        DraftRevisionLockInterceptor draftRevisionLockInterceptor() {
            return INTERCEPTOR;
        }
    }

    @Autowired
    private InvoiceCaseApplicationService commands;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        INTERCEPTOR.reset();
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void submitWaitsForOpenLineMutationAndFreezesTheCommittedLine() throws Exception {
        UUID[] ids = seedOpenCaseWithLine();
        UUID caseId = ids[0];
        UUID revisionId = ids[1];

        CountDownLatch lineInserted = new CountDownLatch(1);
        CountDownLatch releaseInsert = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> inserter = pool.submit(() -> transactions.executeWithoutResult(status -> {
                insertLine(caseId, revisionId, 2, 7, 3000);
                lineInserted.countDown();
                awaitQuietly(releaseInsert);
            }));
            assertThat(lineInserted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<CommandResult<SubmissionResult>> submission = pool.submit(
                    () -> commands.submit(new SubmitInvoiceCaseCommand(caseId, "req-submit", 1L)));
            Thread.sleep(300);
            assertThat(submission.isDone()).isFalse();

            releaseInsert.countDown();
            inserter.get(10, TimeUnit.SECONDS);

            CommandResult<SubmissionResult> result = submission.get(20, TimeUnit.SECONDS);
            assertThat(result.body().status()).isEqualTo(InvoiceCaseStatus.REVIEW_PENDING);
            assertThat(relationalLineFingerprints(revisionId))
                    .isEqualTo(payloadLineFingerprints(caseId))
                    .hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRawLineMutationsAreRejectedAfterSubmitSealsRevision() throws Exception {
        UUID[] ids = seedOpenCaseWithLine();
        UUID caseId = ids[0];
        UUID revisionId = ids[1];
        UUID lineId = lineId(revisionId, 1);

        INTERCEPTOR.arm();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CommandResult<SubmissionResult>> submission = pool.submit(
                    () -> commands.submit(new SubmitInvoiceCaseCommand(caseId, "req-submit", 1L)));
            assertThat(INTERCEPTOR.awaitLocked(10, TimeUnit.SECONDS)).isTrue();

            Future<String> mutation =
                    pool.submit(() -> attempt("update invoice_line set quantity = 99 where id = ?", lineId));
            Thread.sleep(300);
            assertThat(mutation.isDone()).isFalse();

            INTERCEPTOR.resume();
            CommandResult<SubmissionResult> result = submission.get(20, TimeUnit.SECONDS);
            assertThat(result.body().status()).isEqualTo(InvoiceCaseStatus.REVIEW_PENDING);

            assertThat(mutation.get(20, TimeUnit.SECONDS)).isEqualTo("DataIntegrityViolationException");
        } finally {
            INTERCEPTOR.reset();
            pool.shutdownNow();
        }

        for (int i = 0; i < 5; i++) {
            assertThat(attempt("update invoice_line set quantity = ? where id = ?", i + 1, lineId))
                    .isEqualTo("DataIntegrityViolationException");
            assertThat(attempt("delete from invoice_line where id = ?", lineId))
                    .isEqualTo("DataIntegrityViolationException");
            assertThat(attempt(
                            "insert into invoice_line (id, invoice_case_id, draft_revision_id, line_number,"
                                    + " raw_item_name, quantity, unit_price, confirmed_item_id, created_at, updated_at)"
                                    + " values (?, ?, ?, ?, 'Extra', 1, 100, null, ?, ?)",
                            UUID.randomUUID(),
                            caseId,
                            revisionId,
                            10 + i,
                            Timestamp.from(T0),
                            Timestamp.from(T0)))
                    .isEqualTo("DataIntegrityViolationException");
        }

        assertThat(relationalLineFingerprints(revisionId)).isEqualTo(payloadLineFingerprints(caseId));
    }

    private UUID[] seedOpenCaseWithLine() {
        UUID caseId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        Timestamp now = Timestamp.from(T0);
        jdbc.update(
                "insert into invoice_case (id, supplier_id, purchase_order_id, invoice_number,"
                        + " normalized_invoice_number, submitted_by, status, version, created_at, updated_at)"
                        + " values (?, 'SUP-1', 'PO-1', 'INV-1', 'INV1', 'legacy', 'DRAFT', 0, ?, ?)",
                caseId,
                now,
                now);
        jdbc.update(
                "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at)"
                        + " values (?, ?, 1, 'OPEN', ?)",
                revisionId,
                caseId,
                now);
        jdbc.update(
                "update invoice_case set current_draft_revision_id = ?, version = 1, updated_at = ? where id = ?",
                revisionId,
                now,
                caseId);
        insertLine(caseId, revisionId, 1, 1, 100);
        return new UUID[] {caseId, revisionId};
    }

    private void insertLine(UUID caseId, UUID revisionId, int lineNumber, int quantity, long unitPrice) {
        Timestamp now = Timestamp.from(T0);
        jdbc.update(
                "insert into invoice_line (id, invoice_case_id, draft_revision_id, line_number, raw_item_name,"
                        + " quantity, unit_price, confirmed_item_id, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, null, ?, ?)",
                UUID.randomUUID(),
                caseId,
                revisionId,
                lineNumber,
                "Item " + lineNumber,
                quantity,
                unitPrice,
                now,
                now);
    }

    private UUID lineId(UUID revisionId, int lineNumber) {
        return jdbc.queryForObject(
                "select id from invoice_line where draft_revision_id = ? and line_number = ?",
                UUID.class,
                revisionId,
                lineNumber);
    }

    private List<String> relationalLineFingerprints(UUID revisionId) {
        return jdbc.queryForList(
                "select line_number || '|' || raw_item_name || '|' || quantity || '|' || unit_price"
                        + " from invoice_line where draft_revision_id = ? order by line_number",
                String.class,
                revisionId);
    }

    private List<String> payloadLineFingerprints(UUID caseId) {
        String payload = jdbc.queryForObject(
                "select payload::text from evidence_bundle where invoice_case_id = ? order by version_number desc limit 1",
                String.class,
                caseId);
        try {
            JsonNode lines = objectMapper.readTree(payload).get("lines");
            List<String> fingerprints = new ArrayList<>();
            lines.forEach(line -> fingerprints.add(line.get("lineNumber").asInt() + "|"
                    + line.get("rawItemName").asText() + "|"
                    + line.get("quantity").asInt() + "|"
                    + line.get("unitPrice").asLong()));
            return fingerprints;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String attempt(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            return "OK";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Blocks a real submission inside its transaction after it has taken the
     * draft revision lock, so a raw mutation can race the seal deterministically.
     */
    static final class ControlledLockInterceptor implements DraftRevisionLockInterceptor {

        private volatile CountDownLatch locked;
        private volatile CountDownLatch resume;

        void arm() {
            locked = new CountDownLatch(1);
            resume = new CountDownLatch(1);
        }

        void reset() {
            locked = null;
            resume = null;
        }

        boolean awaitLocked(long timeout, TimeUnit unit) throws InterruptedException {
            CountDownLatch current = locked;
            return current != null && current.await(timeout, unit);
        }

        void resume() {
            CountDownLatch current = resume;
            if (current != null) {
                current.countDown();
            }
        }

        @Override
        public void afterLocked(UUID draftRevisionId) {
            CountDownLatch lockedNow = locked;
            CountDownLatch resumeNow = resume;
            if (lockedNow == null) {
                return;
            }
            lockedNow.countDown();
            try {
                if (resumeNow != null) {
                    resumeNow.await(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
