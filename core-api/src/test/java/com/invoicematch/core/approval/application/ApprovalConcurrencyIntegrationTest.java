package com.invoicematch.core.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.approval.domain.InsufficientReceiptBalanceException;
import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.application.ReviewService;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.security.ForbiddenActionException;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real PostgreSQL concurrency and rollback tests for P1-07 approval.
 *
 * <p>A receipt line shared by two claims is the concurrency boundary: exactly
 * one approval consumes the remaining balance and the loser gets a stable 409
 * with the current remaining quantity and no side effect. A concurrent
 * purchasing refresh is serialized by the same advisory lock approval holds, and
 * an injected failure after any written stage rolls the whole approval back.
 */
class ApprovalConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

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
        ApprovalInterceptor approvalInterceptor() {
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
    private ApprovalApplicationService approvals;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private InvoiceCaseApplicationService invoiceCaseCommands;

    @Autowired
    private PurchasingReferenceService purchasingReferenceService;

    @Autowired
    private InvoiceCaseRepository invoiceCases;

    @Autowired
    private ReviewSnapshotRepository reviewSnapshots;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        INTERCEPTOR.reset();
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        STUB.respond(200, sharedReceiptPayload().toJson());
    }

    @Test
    void concurrentApprovalsSharingReceiptRemainingOnlyOneSucceeds() throws Exception {
        UUID first = frozenCase("INV-1", 40);
        UUID second = frozenCase("INV-2", 40);
        ApprovalTarget firstTarget = target(first);
        ApprovalTarget secondTarget = target(second);

        INTERCEPTOR.armExternalFetchBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = pool.submit(() -> attempt(firstTarget));
            Future<Object> b = pool.submit(() -> attempt(secondTarget));

            Object resultA = a.get(30, TimeUnit.SECONDS);
            Object resultB = b.get(30, TimeUnit.SECONDS);

            long successes = List.of(resultA, resultB).stream()
                    .filter(r -> r instanceof ApprovalResult)
                    .count();
            long shortfalls = List.of(resultA, resultB).stream()
                    .filter(r -> r instanceof InsufficientReceiptBalanceException)
                    .count();
            assertThat(successes).isEqualTo(1);
            assertThat(shortfalls).isEqualTo(1);

            InsufficientReceiptBalanceException loser = (InsufficientReceiptBalanceException) List.of(resultA, resultB)
                    .stream()
                    .filter(r -> r instanceof InsufficientReceiptBalanceException)
                    .findFirst()
                    .orElseThrow();
            assertThat(loser.shortfalls()).singleElement().satisfies(shortfall -> {
                assertThat(shortfall.confirmedQuantity()).isEqualTo(60);
                assertThat(shortfall.allocatedQuantity()).isEqualTo(40);
                assertThat(shortfall.remainingQuantity()).isEqualTo(20);
                assertThat(shortfall.requestedQuantity()).isEqualTo(40);
            });

            assertThat(count("receipt_allocation")).isEqualTo(1);
            assertThat(count("payment_request")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "select count(*) from review_decision where decision = 'APPROVED'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "select count(*) from invoice_case where status = 'EXPORT_PENDING'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "select count(*) from invoice_case where status = 'REVIEW_PENDING'", Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            INTERCEPTOR.reset();
        }
    }

    @Test
    void concurrentIdenticalApprovalCreatesOneSetOfEffectsAndConsistentReplay() throws Exception {
        UUID caseId = frozenCase("INV-1", 40);
        ApprovalTarget target = target(caseId);

        INTERCEPTOR.armExternalFetchBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = pool.submit(() -> attempt(target));
            Future<Object> b = pool.submit(() -> attempt(target));

            Object resultA = a.get(30, TimeUnit.SECONDS);
            Object resultB = b.get(30, TimeUnit.SECONDS);
            assertThat(resultA).isInstanceOf(ApprovalResult.class);
            assertThat(resultB).isInstanceOf(ApprovalResult.class);
            assertThat((ApprovalResult) resultA).isEqualTo(resultB);

            assertThat(count("review_decision")).isEqualTo(1);
            assertThat(count("receipt_allocation")).isEqualTo(1);
            assertThat(count("payment_request")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            INTERCEPTOR.reset();
        }
    }

    @Test
    void purchasingRefreshWaitsForTheApprovalAdvisoryLock() throws Exception {
        UUID caseId = frozenCase("INV-1", 40);
        ApprovalTarget target = target(caseId);

        INTERCEPTOR.armPurchaseOrderLockBarrier();
        ExecutorService approvalPool = Executors.newSingleThreadExecutor();
        ExecutorService refreshPool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> approval = approvalPool.submit(() -> attempt(target));
            assertThat(INTERCEPTOR.awaitPurchaseOrderLocked(10, TimeUnit.SECONDS)).isTrue();

            Future<?> refresh = refreshPool.submit(() -> purchasingReferenceService.refresh(
                    new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of(SUPPLIER))));

            Thread.sleep(700);
            assertThat(refresh.isDone())
                    .as("refresh must wait for the approval's purchase order lock")
                    .isFalse();

            INTERCEPTOR.resumePurchaseOrderLock();
            assertThat(approval.get(30, TimeUnit.SECONDS)).isInstanceOf(ApprovalResult.class);
            refresh.get(30, TimeUnit.SECONDS);
            assertThat(count("receipt_allocation")).isEqualTo(1);
        } finally {
            INTERCEPTOR.reset();
            approvalPool.shutdownNow();
            refreshPool.shutdownNow();
        }
    }

    @Test
    void failureAfterEachWrittenStageRollsBackTheWholeApproval() throws Exception {
        UUID caseId = frozenCase("INV-1", 40);

        for (ApprovalInterceptorStage stage : ApprovalInterceptorStage.values()) {
            ApprovalTarget target = target(caseId, "req-" + stage.name());
            INTERCEPTOR.failAt(stage);
            Object result = attempt(target);
            assertThat(result).isInstanceOf(IllegalStateException.class)
                    .as("stage " + stage + " must fail the transaction");

            assertThat(count("review_decision")).isZero();
            assertThat(count("receipt_allocation")).isZero();
            assertThat(count("payment_request")).isZero();
            assertThat(currentStatus(caseId)).isEqualTo("REVIEW_PENDING");
            assertThat(remaining("RCL-1001-1-1")).isEqualTo(60);
        }

        INTERCEPTOR.reset();
        ApprovalTarget target = target(caseId, "req-final");
        assertThat(attempt(target)).isInstanceOf(ApprovalResult.class);
        assertThat(count("receipt_allocation")).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsOnDifferentPurchaseOrdersWithMultipleReceiptLinesDoNotDeadlock() throws Exception {
        STUB.respond(200, multiLinePayload(PO_ID).toJson());
        STUB.respondFor("PO-2001", multiLinePayload("PO-2001").toJson());

        UUID first = frozenMultiLineCase(PO_ID, "INV-1", 40, 20);
        UUID second = frozenMultiLineCase("PO-2001", "INV-2", 40, 20);
        ApprovalTarget firstTarget = target(first);
        ApprovalTarget secondTarget = target(second);

        INTERCEPTOR.armExternalFetchBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = pool.submit(() -> attempt(firstTarget));
            Future<Object> b = pool.submit(() -> attempt(secondTarget));

            assertThat(a.get(30, TimeUnit.SECONDS)).isInstanceOf(ApprovalResult.class);
            assertThat(b.get(30, TimeUnit.SECONDS)).isInstanceOf(ApprovalResult.class);
            assertThat(count("receipt_allocation")).isEqualTo(6);
        } finally {
            pool.shutdownNow();
            INTERCEPTOR.reset();
        }
    }

    @Test
    void directServiceApprovalWithoutApproverRoleIsForbidden() {
        UUID caseId = frozenCase("INV-1", 40);
        ApprovalTarget target = target(caseId);

        TestActors.clear();
        assertThatThrownBy(() -> approvals.approve(new ApproveInvoiceCaseCommand(
                        target.caseId(), target.requestId(), target.version(),
                        target.snapshotId(), target.payloadHash())))
                .isInstanceOf(ForbiddenActionException.class);
        assertThat(count("receipt_allocation")).isZero();
        assertThat(count("payment_request")).isZero();
    }

    private Object attempt(ApprovalTarget target) {
        try {
            return TestActors.call("approver", "APPROVER", () -> approvals
                    .approve(new ApproveInvoiceCaseCommand(
                            target.caseId(), target.requestId(), target.version(),
                            target.snapshotId(), target.payloadHash()))
                    .body());
        } catch (RuntimeException e) {
            return e;
        }
    }

    private UUID frozenCase(String invoiceNumber, int quantity) {
        UUID caseId = TestActors.call("submitter", "SUBMITTER", () -> {
            UUID id = invoiceCaseCommands
                    .create(new CreateInvoiceCaseCommand("create-" + invoiceNumber, SUPPLIER, PO_ID, invoiceNumber))
                    .body()
                    .id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    id,
                    "draft-" + invoiceNumber,
                    caseVersion(id),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", quantity, 2500, ITEM_A))));
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(id, "submit-" + invoiceNumber, caseVersion(id)));
            return id;
        });
        TestActors.run("operator", "OPERATOR", () -> matchingService.run(new RunMatchCommand(caseId, "match-" + invoiceNumber)));
        TestActors.run("approver", "APPROVER", () -> reviewService.freezeSnapshot(
                new FreezeReviewSnapshotCommand(caseId, "snap-" + invoiceNumber, caseVersion(caseId))));
        return caseId;
    }

    private UUID frozenMultiLineCase(String purchaseOrderId, String invoiceNumber, int firstQty, int secondQty) {
        UUID caseId = TestActors.call("submitter", "SUBMITTER", () -> {
            UUID id = invoiceCaseCommands
                    .create(new CreateInvoiceCaseCommand(
                            "create-" + invoiceNumber, SUPPLIER, purchaseOrderId, invoiceNumber))
                    .body()
                    .id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    id,
                    "draft-" + invoiceNumber,
                    caseVersion(id),
                    List.of(
                            new InvoiceLineInput(1, "Premium Copy Paper A4", firstQty, 2500, ITEM_A),
                            new InvoiceLineInput(2, "Premium Copy Paper A4 again", secondQty, 2500, ITEM_A))));
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(id, "submit-" + invoiceNumber, caseVersion(id)));
            return id;
        });
        TestActors.run("operator", "OPERATOR", () ->
                matchingService.run(new RunMatchCommand(caseId, "match-" + invoiceNumber)));
        TestActors.run("approver", "APPROVER", () -> reviewService.freezeSnapshot(
                new FreezeReviewSnapshotCommand(caseId, "snap-" + invoiceNumber, caseVersion(caseId))));
        return caseId;
    }

    private ApprovalTarget target(UUID caseId) {
        return target(caseId, "approve-" + caseId);
    }

    private ApprovalTarget target(UUID caseId, String requestId) {
        ReviewSnapshot snapshot = reviewSnapshots
                .findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId)
                .orElseThrow();
        return new ApprovalTarget(
                caseId, requestId, caseVersion(caseId), snapshot.id(), snapshot.payloadHash());
    }

    private PurchasingPayloads sharedReceiptPayload() {
        return new PurchasingPayloads()
                .snapshotVersion(5)
                .addLine("POL-1001-1", ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                .addReceipt("RCV-1001-1", "CONFIRMED", "2026-01-05", 2,
                        PurchasingPayloads.receiptLine("RCL-1001-1-1", 2, "POL-1001-1", 60));
    }

    private PurchasingPayloads multiLinePayload(String purchaseOrderId) {
        String lineId = "POL-" + purchaseOrderId + "-1";
        return new PurchasingPayloads()
                .snapshotVersion(5)
                .purchaseOrderId(purchaseOrderId)
                .addLine(lineId, ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                .addReceipt("RCV-A-" + purchaseOrderId, "CONFIRMED", "2026-01-05", 2,
                        PurchasingPayloads.receiptLine("RCL-A-" + purchaseOrderId, 2, lineId, 30))
                .addReceipt("RCV-B-" + purchaseOrderId, "CONFIRMED", "2026-01-06", 2,
                        PurchasingPayloads.receiptLine("RCL-B-" + purchaseOrderId, 2, lineId, 30));
    }

    private long caseVersion(UUID caseId) {
        return invoiceCases.findById(caseId).orElseThrow().version();
    }

    private String currentStatus(UUID caseId) {
        return invoiceCases.findById(caseId).orElseThrow().status().name();
    }

    private int remaining(String receiptLineId) {
        return jdbc.queryForObject(
                "select confirmed_quantity - coalesce((select sum(allocated_quantity) from receipt_allocation a"
                        + " where a.receipt_line_snapshot_id = r.id), 0) from receipt_line_snapshot r"
                        + " where r.receipt_line_id = ?",
                Integer.class,
                receiptLineId);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private record ApprovalTarget(
            UUID caseId, String requestId, long version, UUID snapshotId, String payloadHash) {
    }

    enum ApprovalInterceptorStage {
        DECISION,
        ALLOCATION,
        PAYMENT
    }

    /**
     * Test seam that can synchronize after the external fetch, hold the purchase
     * order lock, and inject a failure after any written stage.
     */
    static final class ControlledInterceptor implements ApprovalInterceptor {

        private volatile CountDownLatch externalFetchReached;
        private volatile CountDownLatch externalFetchResume;
        private volatile CountDownLatch purchaseOrderLockedReached;
        private volatile CountDownLatch purchaseOrderLockResume;
        private final AtomicReference<ApprovalInterceptorStage> failAt = new AtomicReference<>();

        void reset() {
            externalFetchReached = null;
            externalFetchResume = null;
            purchaseOrderLockedReached = null;
            purchaseOrderLockResume = null;
            failAt.set(null);
        }

        void armExternalFetchBarrier(int parties) {
            externalFetchReached = new CountDownLatch(parties);
            externalFetchResume = new CountDownLatch(1);
        }

        void armPurchaseOrderLockBarrier() {
            purchaseOrderLockedReached = new CountDownLatch(1);
            purchaseOrderLockResume = new CountDownLatch(1);
        }

        boolean awaitPurchaseOrderLocked(long timeout, TimeUnit unit) throws InterruptedException {
            CountDownLatch current = purchaseOrderLockedReached;
            return current != null && current.await(timeout, unit);
        }

        void resumePurchaseOrderLock() {
            CountDownLatch current = purchaseOrderLockResume;
            if (current != null) {
                current.countDown();
            }
        }

        void failAt(ApprovalInterceptorStage stage) {
            reset();
            failAt.set(stage);
        }

        private void failIfStage(ApprovalInterceptorStage stage) {
            if (failAt.get() == stage) {
                throw new IllegalStateException("injected failure at " + stage);
            }
        }

        @Override
        public void afterExternalFetch(UUID caseId) {
            CountDownLatch reached = externalFetchReached;
            CountDownLatch resume = externalFetchResume;
            if (reached == null) {
                return;
            }
            reached.countDown();
            try {
                resume.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void afterPurchaseOrderLocked(UUID caseId) {
            CountDownLatch reached = purchaseOrderLockedReached;
            CountDownLatch resume = purchaseOrderLockResume;
            if (reached != null && reached.getCount() > 0) {
                reached.countDown();
                try {
                    resume.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void afterDecisionWritten(UUID caseId) {
            failIfStage(ApprovalInterceptorStage.DECISION);
        }

        @Override
        public void afterAllocationsWritten(UUID caseId) {
            failIfStage(ApprovalInterceptorStage.ALLOCATION);
        }

        @Override
        public void afterPaymentRequestWritten(UUID caseId) {
            failIfStage(ApprovalInterceptorStage.PAYMENT);
        }
    }
}
