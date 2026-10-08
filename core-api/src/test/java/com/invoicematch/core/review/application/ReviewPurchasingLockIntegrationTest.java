package com.invoicematch.core.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotLock;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import com.invoicematch.core.support.TestActors;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL interleaving tests for the P1-05 review/purchasing lock
 * contract. A review write and a purchasing snapshot refresh must take the same
 * purchase order advisory lock, so the review's final currentness validation
 * and its committed decision/snapshot cannot be separated by a refresh.
 */
class ReviewPurchasingLockIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final String ITEM_A = "ITEM-A4-80";
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
        ReviewLockInterceptor reviewLockInterceptor() {
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
    private ReviewService reviewService;

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private InvoiceCaseApplicationService invoiceCaseCommands;

    @Autowired
    private PurchasingReferenceService purchasingReferenceService;

    @Autowired
    private InvoiceCaseRepository invoiceCaseRepository;

    @Autowired
    private ReviewSnapshotRepository reviewSnapshots;

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

    @Autowired
    private ReviewSnapshotWriter snapshotWriter;

    @Test
    void snapshotWriterRequiresTheReviewTransaction() {
        assertThatThrownBy(
                        () -> snapshotWriter.nextSnapshotNumber(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void outerRollbackRemovesSnapshotAuditAndReplayTogether() {
        UUID caseId = frozenCase();
        ReviewSnapshot original = latestSnapshot(caseId);
        long version = caseVersion(caseId);
        int auditsBefore = count("audit_entry");
        int requestsBefore = count("idempotency_record");
        FreezeReviewSnapshotCommand command = new FreezeReviewSnapshotCommand(caseId, "rollback-freeze", version);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var saved = TestActors.call("approver", "APPROVER", () -> reviewService.freezeSnapshot(command));
            assertThat(saved.body().snapshotNumber()).isEqualTo(original.snapshotNumber() + 1);
            assertThat(count("review_snapshot")).isEqualTo(2);
            status.setRollbackOnly();
        });

        assertThat(count("review_snapshot")).isEqualTo(1);
        assertThat(count("audit_entry")).isEqualTo(auditsBefore);
        assertThat(count("idempotency_record")).isEqualTo(requestsBefore);
        assertThat(caseVersion(caseId)).isEqualTo(version);
        var retried = TestActors.call("approver", "APPROVER", () -> reviewService.freezeSnapshot(command));
        assertThat(retried.body().snapshotNumber()).isEqualTo(original.snapshotNumber() + 1);
    }

    @Test
    void reviewHoldingThePurchaseOrderLockBlocksAConcurrentRefresh() throws Exception {
        UUID caseId = frozenCase();
        ReviewSnapshot snapshot = latestSnapshot(caseId);
        long version = caseVersion(caseId);

        INTERCEPTOR.arm();
        ExecutorService reviewPool = Executors.newSingleThreadExecutor();
        ExecutorService refreshPool = Executors.newSingleThreadExecutor();
        try {
            Future<?> review = reviewPool.submit(() -> TestActors.call("approver", "APPROVER",
                    () -> reviewService.recordMapping(new RecordMappingDecisionCommand(
                            caseId, "map-1", version, snapshot.id(), snapshot.payloadHash(), 1, ITEM_A))));
            assertThat(INTERCEPTOR.awaitPurchaseOrderLocked(10, TimeUnit.SECONDS)).isTrue();

            Future<?> refresh = refreshPool.submit(() -> purchasingReferenceService.refresh(
                    new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of(SUPPLIER))));

            Thread.sleep(700);
            assertThat(refresh.isDone())
                    .as("refresh must wait for the review write's purchase order lock")
                    .isFalse();

            INTERCEPTOR.resume();

            review.get(30, TimeUnit.SECONDS);
            refresh.get(30, TimeUnit.SECONDS);
            assertThat(count("review_decision")).isEqualTo(1);
            assertThat(count("review_snapshot")).isEqualTo(2);
        } finally {
            INTERCEPTOR.reset();
            reviewPool.shutdownNow();
            refreshPool.shutdownNow();
        }
    }

    @Test
    void refreshHoldingThePurchaseOrderLockBlocksAReviewWrite() throws Exception {
        UUID caseId = frozenCase();
        ReviewSnapshot snapshot = latestSnapshot(caseId);
        long version = caseVersion(caseId);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holderPool = Executors.newSingleThreadExecutor();
        ExecutorService reviewPool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = holderPool.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.executeWithoutResult(status -> {
                    jdbc.queryForList(
                            "select pg_advisory_xact_lock(?, hashtext(?))",
                            PurchaseOrderSnapshotLock.LOCK_CLASS,
                            PO_ID);
                    held.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> review = reviewPool.submit(() -> TestActors.call("approver", "APPROVER",
                    () -> reviewService.recordMapping(new RecordMappingDecisionCommand(
                            caseId, "map-1", version, snapshot.id(), snapshot.payloadHash(), 1, ITEM_A))));

            Thread.sleep(700);
            assertThat(review.isDone())
                    .as("review must wait for the purchase order lock held by the refresh")
                    .isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            review.get(30, TimeUnit.SECONDS);
            assertThat(count("review_decision")).isEqualTo(1);
        } finally {
            release.countDown();
            holderPool.shutdownNow();
            reviewPool.shutdownNow();
        }
    }

    private UUID frozenCase() {
        UUID caseId = TestActors.call("submitter", "SUBMITTER", () -> {
            UUID id = invoiceCaseCommands
                    .create(new CreateInvoiceCaseCommand("c-1", SUPPLIER, PO_ID, "INV-1"))
                    .body()
                    .id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    id,
                    "c-2",
                    caseVersion(id),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", 10, 2500, null))));
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(id, "c-3", caseVersion(id)));
            return id;
        });
        TestActors.run("operator", "OPERATOR", () -> matchingService.run(new RunMatchCommand(caseId, "m-1")));
        TestActors.run("approver", "APPROVER", () -> reviewService.freezeSnapshot(
                new FreezeReviewSnapshotCommand(caseId, "s-1", caseVersion(caseId))));
        return caseId;
    }

    private ReviewSnapshot latestSnapshot(UUID caseId) {
        return reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId).orElseThrow();
    }

    private long caseVersion(UUID caseId) {
        return invoiceCaseRepository.findById(caseId).orElseThrow().version();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /**
     * Blocks a review write after it has taken the case row lock and the
     * purchase order advisory lock and before its currentness validation.
     */
    static final class ControlledInterceptor implements ReviewLockInterceptor {

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

        boolean awaitPurchaseOrderLocked(long timeout, TimeUnit unit) throws InterruptedException {
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
        public void afterPurchaseOrderLocked(UUID caseId) {
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
