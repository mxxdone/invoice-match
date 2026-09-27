package com.invoicematch.core.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceCaseDetail;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.domain.MatchStateConflictException;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import com.invoicematch.core.support.TestActors;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic PostgreSQL concurrency tests for the P1-04 case-lock ordering.
 *
 * <p>Matching takes the invoice case row lock before reading the case state and
 * the latest bundle and holds it through the result insert. Two interleavings
 * are proven: match owns the lock first (a state-changing writer waits and can
 * never interleave), and a state-changing writer owns the lock first (matching
 * waits and then observes the new state, so it cannot mix an old case version
 * with a new bundle).
 */
class MatchConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final String ITEM_A = "ITEM-A4-80";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final StubPurchasingServer STUB;
    private static final ControlledInterceptor INTERCEPTOR = new ControlledInterceptor();

    static {
        try {
            STUB = new StubPurchasingServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TestConfiguration
    static class InterceptorConfiguration {
        @Bean
        MatchLockInterceptor matchLockInterceptor() {
            return INTERCEPTOR;
        }
    }

    @DynamicPropertySource
    static void purchasingProperties(DynamicPropertyRegistry registry) {
        registry.add("purchasing-system.base-url", STUB::baseUrl);
        registry.add("purchasing-system.connect-timeout", () -> "1s");
        registry.add("purchasing-system.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private InvoiceCaseApplicationService invoiceCaseCommands;

    @Autowired
    private InvoiceCaseRepository invoiceCaseRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        INTERCEPTOR.reset();
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
    }

    @Test
    void matchOwningTheCaseLockFirstBlocksStateWriterAndUsesTheBundleItRead() throws Exception {
        UUID caseId = submittedCase();
        long caseVersion = caseVersion(caseId);

        INTERCEPTOR.arm();
        ExecutorService matchPool = Executors.newSingleThreadExecutor();
        ExecutorService writerPool = Executors.newSingleThreadExecutor();
        try {
            Future<CommandResult<MatchResultView>> matchFuture = matchPool.submit(
                    () -> TestActors.call("operator", "OPERATOR",
                            () -> matchingService.run(new RunMatchCommand(caseId, "m-1"))));
            assertThat(INTERCEPTOR.awaitCaseLocked(10, TimeUnit.SECONDS)).isTrue();

            Future<Void> writerFuture = writerPool.submit(() -> {
                stateChangingWriter(caseId, false, null, null);
                return null;
            });

            Thread.sleep(700);
            assertThat(writerFuture.isDone())
                    .as("state-changing writer must wait for the case lock match holds")
                    .isFalse();

            INTERCEPTOR.resume();

            MatchResultView result = matchFuture.get(20, TimeUnit.SECONDS).body();
            assertThat(result.resultNumber()).isEqualTo(1);
            assertThat(result.payload().get("evidenceBundle").get("version").asInt()).isEqualTo(1);
            assertThat(result.payload().get("caseVersion").asLong()).isEqualTo(caseVersion);

            writerFuture.get(20, TimeUnit.SECONDS);
            assertThat(status(caseId)).isEqualTo("SUPPLEMENT_REQUIRED");
            assertThat(count("match_result")).isEqualTo(1);
        } finally {
            INTERCEPTOR.reset();
            matchPool.shutdownNow();
            writerPool.shutdownNow();
        }
    }

    @Test
    void stateWriterOwningTheCaseLockFirstMakesMatchingSeeNewStateAndNotAppend() throws Exception {
        UUID caseId = submittedCase();

        CountDownLatch writerHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ExecutorService writerPool = Executors.newSingleThreadExecutor();
        ExecutorService matchPool = Executors.newSingleThreadExecutor();
        try {
            Future<Void> writerFuture = writerPool.submit(() -> {
                stateChangingWriter(caseId, true, writerHoldsLock, releaseWriter);
                return null;
            });
            assertThat(writerHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<CommandResult<MatchResultView>> matchFuture = matchPool.submit(
                    () -> TestActors.call("operator", "OPERATOR",
                            () -> matchingService.run(new RunMatchCommand(caseId, "m-1"))));

            Thread.sleep(700);
            assertThat(matchFuture.isDone())
                    .as("matching must wait for the state writer's case lock")
                    .isFalse();

            releaseWriter.countDown();
            writerFuture.get(20, TimeUnit.SECONDS);

            assertThatThrownBy(() -> matchFuture.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(MatchStateConflictException.class);
            assertThat(count("match_result")).isZero();
        } finally {
            releaseWriter.countDown();
            writerPool.shutdownNow();
            matchPool.shutdownNow();
        }
    }

    private void stateChangingWriter(
            UUID caseId, boolean pauseAfterLock, CountDownLatch writerHoldsLock, CountDownLatch releaseWriter) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            invoiceCaseRepository.findByIdForUpdate(caseId).orElseThrow();
            if (pauseAfterLock) {
                writerHoldsLock.countDown();
                try {
                    releaseWriter.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            UUID draftId = UUID.randomUUID();
            jdbc.update(
                    "insert into draft_revision (id, invoice_case_id, revision_number, status, created_at,"
                            + " sealed_at) values (?, ?, 2, 'SEALED', ?, ?)",
                    draftId,
                    caseId,
                    Timestamp.from(T0),
                    Timestamp.from(T0));
            jdbc.update(
                    "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                            + " payload_hash, payload, submitted_at) values (?, ?, ?, 2, 'hash-2', '{}'::jsonb, ?)",
                    UUID.randomUUID(),
                    caseId,
                    draftId,
                    Timestamp.from(T0));
            jdbc.update(
                    "update invoice_case set status = 'SUPPLEMENT_REQUIRED', version = version + 1,"
                            + " updated_at = updated_at + interval '1 second' where id = ?",
                    caseId);
        });
    }

    private UUID submittedCase() {
        return TestActors.call("submitter", "SUBMITTER", () -> {
            CommandResult<InvoiceCaseDetail> created = invoiceCaseCommands.create(
                    new CreateInvoiceCaseCommand("c-1", SUPPLIER, PO_ID, "INV-1"));
            UUID caseId = created.body().id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    caseId, "c-2", created.body().version(), List.of(new InvoiceLineInput(1, "A4 Paper", 10, 2500, ITEM_A))));
            long version = invoiceCaseRepository.findById(caseId).orElseThrow().version();
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(caseId, "c-3", version));
            return caseId;
        });
    }

    private long caseVersion(UUID caseId) {
        return invoiceCaseRepository.findById(caseId).orElseThrow().version();
    }

    private String status(UUID caseId) {
        return invoiceCaseRepository.findById(caseId).orElseThrow().status().name();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /**
     * Blocks matching after it has taken the case row lock and before it reads
     * the latest bundle, until the test resumes it.
     */
    static final class ControlledInterceptor implements MatchLockInterceptor {

        private volatile CountDownLatch reached;
        private volatile CountDownLatch resume;

        void arm() {
            reached = new CountDownLatch(1);
            resume = new CountDownLatch(1);
        }

        void reset() {
            reached = null;
            resume = null;
        }

        boolean awaitCaseLocked(long timeout, TimeUnit unit) throws InterruptedException {
            CountDownLatch current = reached;
            return current != null && current.await(timeout, unit);
        }

        void resume() {
            CountDownLatch current = resume;
            if (current != null) {
                current.countDown();
            }
        }

        @Override
        public void afterCaseLocked(UUID caseId) {
            CountDownLatch reachedNow = reached;
            CountDownLatch resumeNow = resume;
            if (reachedNow == null) {
                return;
            }
            reachedNow.countDown();
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
