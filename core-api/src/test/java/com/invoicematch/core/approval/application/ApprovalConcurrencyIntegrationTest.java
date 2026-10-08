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
import com.invoicematch.core.purchasingreference.domain.ReceiptAllocationProtectedException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApprovalReceiptAllocator receiptAllocator;

    @Test
    void receiptValidationCannotAcquireLocksOutsideTheApprovalTransaction() {
        assertThatThrownBy(() -> receiptAllocator.resolveAndLockReceiptLines(null, null, PO_ID, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

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
            assertThat(count("outbox_event")).isZero();
            assertThat(jdbc.queryForObject(
                            "select count(*) from audit_entry where invoice_case_id = ? and action = 'APPROVE'",
                            Integer.class,
                            caseId))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "select count(*) from idempotency_record where scope = 'invoice-case:approve'"
                                    + " and actor = 'approver' and request_id = ?",
                            Integer.class,
                            target.requestId()))
                    .isZero();
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

    @Test
    void approvalIsRejectedInsideAnOuterTransactionBeforeTheExternalFetch() {
        UUID caseId = frozenCase("INV-1", 40);
        ApprovalTarget target = target(caseId);
        int fetchesBefore = STUB.requestCount();

        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
        AtomicReference<Object> thrown = new AtomicReference<>();
        callerTransaction.executeWithoutResult(status -> {
            // Hold a row lock on the SAME case the approval targets. With
            // Propagation.NEVER, approval must fail immediately without running
            // any body, HTTP call or nested transaction (so it can never deadlock
            // reacquiring the case).
            jdbc.queryForList("select id from invoice_case where id = ? for update", caseId);
            try {
                thrown.set(attempt(target));
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });

        assertThat(thrown.get()).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(STUB.requestCount()).isEqualTo(fetchesBefore);
        assertThat(INTERCEPTOR.externalFetchSawActiveTransaction()).isNull();
        assertThat(count("receipt_allocation")).isZero();
        assertThat(count("payment_request")).isZero();
        assertThat(currentStatus(caseId)).isEqualTo("REVIEW_PENDING");
    }

    @Test
    void concurrentApprovalsSharingTwoReceiptLinesAreAllOrNothing() throws Exception {
        STUB.respond(200, sharedTwoLinePayload().toJson());
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
            assertThat(successes).isEqualTo(1);
            assertThat(List.of(resultA, resultB)).anyMatch(r -> r instanceof InsufficientReceiptBalanceException);

            // The winner wrote its whole plan (both receipt lines) or nothing.
            assertThat(count("receipt_allocation")).isEqualTo(2);
            assertThat(count("payment_request")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "select coalesce(sum(allocated_quantity), 0) from receipt_allocation", Long.class))
                    .isEqualTo(40L);
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
    void refreshCannotReduceOrDeactivateAnAllocatedReceiptLine() {
        UUID caseId = frozenCase("INV-1", 40);
        assertThat(attempt(target(caseId))).isInstanceOf(ApprovalResult.class);
        UUID receiptLineSnapshotId = jdbc.queryForObject(
                "select id from receipt_line_snapshot where receipt_line_id = 'RCL-1001-1-1'", UUID.class);

        // Decreasing below the committed allocation is rejected.
        assertThatThrownBy(() -> jdbc.update(
                        "update receipt_line_snapshot set confirmed_quantity = 0 where id = ?",
                        receiptLineSnapshotId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("below committed allocation");

        // Deactivating a line with allocations is rejected.
        assertThatThrownBy(() -> jdbc.update(
                        "update receipt_line_snapshot set active = false where id = ?", receiptLineSnapshotId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be deactivated");

        // Increasing the confirmed quantity and evolving the version stays allowed.
        jdbc.update("update receipt_line_snapshot set confirmed_quantity = 100, receipt_line_version = 3"
                + " where id = ?", receiptLineSnapshotId);
        assertThat(jdbc.queryForObject(
                        "select confirmed_quantity from receipt_line_snapshot where id = ?",
                        Integer.class,
                        receiptLineSnapshotId))
                .isEqualTo(100);

        // History keeps proving the approved basis.
        assertThat(jdbc.queryForObject(
                        "select confirmed_quantity_at_approval from receipt_allocation where receipt_line_snapshot_id = ?",
                        Integer.class,
                        receiptLineSnapshotId))
                .isEqualTo(60);
    }

    @Test
    void refreshThroughTheStoreRejectsReducingAnAllocatedLineWithStableError() {
        UUID caseId = frozenCase("INV-1", 40);
        assertThat(attempt(target(caseId))).isInstanceOf(ApprovalResult.class);

        PurchasingPayloads correction = sharedReceiptPayload()
                .snapshotVersion(6)
                .clearReceipts()
                .addReceipt("RCV-1001-1", "CONFIRMED", "2026-01-05", 2,
                        PurchasingPayloads.receiptLine("RCL-1001-1-1", 2, "POL-1001-1", 10));
        STUB.respond(200, correction.toJson());

        assertThatThrownBy(() -> TestActors.run("operator", "OPERATOR", () -> purchasingReferenceService.refresh(
                        new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of(SUPPLIER)))))
                .isInstanceOf(ReceiptAllocationProtectedException.class);

        // The refresh rolled back: no mixed snapshot and the allocation intact.
        assertThat(jdbc.queryForObject(
                        "select snapshot_version from purchase_order_snapshot where purchase_order_id = 'PO-1001'",
                        Long.class))
                .isEqualTo(5L);
        assertThat(count("receipt_allocation")).isEqualTo(1);
    }

    @Test
    void concurrentApprovalCaseLockAndRawAllocationInsertDoNotDeadlock() throws Exception {
        STUB.respond(200, spareLinePayload().toJson());
        UUID caseId = frozenCase("INV-1", 40);
        assertThat(attempt(target(caseId))).isInstanceOf(ApprovalResult.class);
        UUID decisionId = jdbc.queryForObject(
                "select id from review_decision where invoice_case_id = ? and decision = 'APPROVED'",
                UUID.class,
                caseId);
        UUID snapshotId = jdbc.queryForObject(
                "select review_snapshot_id from payment_request where invoice_case_id = ?", UUID.class, caseId);
        UUID bundleId = jdbc.queryForObject(
                "select evidence_bundle_id from payment_request where invoice_case_id = ?", UUID.class, caseId);
        String payloadHash = jdbc.queryForObject(
                "select review_payload_hash from payment_request where invoice_case_id = ?", String.class, caseId);
        UUID spareReceiptLineId = jdbc.queryForObject(
                "select id from receipt_line_snapshot where receipt_line_id = 'RCL-B-1001'", UUID.class);
        long spareReceiptLineVersion = jdbc.queryForObject(
                "select receipt_line_version from receipt_line_snapshot where id = ?", Long.class, spareReceiptLineId);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate holder = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> held = pool.submit(() -> holder.executeWithoutResult(status -> {
                jdbc.queryForList("select id from invoice_case where id = ? for update", caseId);
                jdbc.queryForList("select pg_advisory_xact_lock(1, hashtext(?))", PO_ID);
                jdbc.queryForList(
                        "select id from receipt_line_snapshot where receipt_line_id = 'RCL-A-1001' for update");
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // Raw insert for the same PO must wait for the holder in the canonical
            // case -> advisory -> receipt order, not deadlock.
            Future<Integer> insert = pool.submit(() -> jdbc.update(
                    "insert into receipt_allocation (id, invoice_case_id, purchase_order_id, review_decision_id,"
                            + " review_snapshot_id, review_payload_hash, evidence_bundle_id, invoice_line_number,"
                            + " receipt_line_snapshot_id, receipt_id, receipt_line_id, purchase_order_line_id,"
                            + " receipt_line_version, confirmed_quantity_at_approval, allocated_quantity, created_at)"
                            + " values (?, ?, 'PO-1001', ?, ?, ?, ?, 1, ?, 'RCV-B-1001', 'RCL-B-1001',"
                            + " 'POL-1001-1', ?, 20, 1, now())",
                    UUID.randomUUID(),
                    caseId,
                    decisionId,
                    snapshotId,
                    payloadHash,
                    bundleId,
                    spareReceiptLineId,
                    spareReceiptLineVersion));

            Thread.sleep(500);
            assertThat(insert.isDone()).as("raw insert must block on the canonical locks").isFalse();

            release.countDown();
            held.get(30, TimeUnit.SECONDS);
            assertThat(insert.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(count("receipt_allocation")).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rawAllocationInsertWaitsForTheCanonicalCaseThenReceiptLockWithoutDeadlock() throws Exception {
        STUB.respond(200, spareLinePayload().toJson());
        UUID caseId = frozenCase("INV-1", 40);
        assertThat(attempt(target(caseId))).isInstanceOf(ApprovalResult.class);
        ApprovedSubject subject = approvedSubject(caseId);
        ReceiptFact spare = receiptFact("RCL-B-1001");

        CountDownLatch caseLocked = new CountDownLatch(1);
        CountDownLatch lockSpareReceipt = new CountDownLatch(1);
        TransactionTemplate holder = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Connection A is the exact reverse-order acquisition the old
            // two-trigger schema could deadlock on: hold the case row, then lock
            // the receipt line the raw writer wants.
            Future<?> held = pool.submit(() -> holder.executeWithoutResult(status -> {
                jdbc.queryForList("select id from invoice_case where id = ? for update", caseId);
                caseLocked.countDown();
                try {
                    lockSpareReceipt.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.queryForList("select id from receipt_line_snapshot where id = ? for update", spare.id());
            }));
            assertThat(caseLocked.await(10, TimeUnit.SECONDS)).isTrue();

            // Connection B raw-inserts a valid allocation for that same receipt
            // line. The single canonical guard must take the case lock first, so
            // B waits on A without ever holding the receipt; a reverse guard
            // would make PostgreSQL report a deadlock.
            Future<Integer> insert = pool.submit(() -> rawInsertAllocation(caseId, subject, 1, spare, 1));

            Thread.sleep(500);
            assertThat(insert.isDone())
                    .as("raw insert must wait on the case lock before touching the receipt")
                    .isFalse();

            lockSpareReceipt.countDown();
            held.get(30, TimeUnit.SECONDS);
            assertThat(insert.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(count("receipt_allocation")).isEqualTo(2);
        } finally {
            lockSpareReceipt.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void reverseOrderRawMultiRowAllocationsOnTheSamePurchaseOrderDoNotDeadlock() throws Exception {
        STUB.respond(200, reverseMultiRowPayload().toJson());
        UUID first = frozenMultiLineCase(PO_ID, "INV-1", 10, 10);
        UUID second = frozenMultiLineCase(PO_ID, "INV-2", 10, 10);
        assertThat(attempt(target(first))).isInstanceOf(ApprovalResult.class);
        assertThat(attempt(target(second))).isInstanceOf(ApprovalResult.class);
        ApprovedSubject firstSubject = approvedSubject(first);
        ApprovedSubject secondSubject = approvedSubject(second);
        ReceiptFact receiptB = receiptFact("RCL-B-1001");
        ReceiptFact receiptC = receiptFact("RCL-C-1001");

        CountDownLatch start = new CountDownLatch(1);
        TransactionTemplate writerTx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Two raw writers each insert two rows in one transaction, touching
            // the same two receipt lines in opposite order. They share the
            // purchase order advisory lock, so the second writer waits before any
            // receipt lock instead of forming a cycle.
            Future<Integer> forward = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return writerTx.execute(status -> rawInsertAllocation(first, firstSubject, 1, receiptB, 1)
                        + rawInsertAllocation(first, firstSubject, 2, receiptC, 1));
            });
            Future<Integer> reverse = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return writerTx.execute(status -> rawInsertAllocation(second, secondSubject, 2, receiptC, 1)
                        + rawInsertAllocation(second, secondSubject, 1, receiptB, 1));
            });
            start.countDown();
            assertThat(forward.get(30, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(reverse.get(30, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(count("receipt_allocation")).isEqualTo(8);
        } finally {
            pool.shutdownNow();
        }
    }

    private int rawInsertAllocation(
            UUID caseId, ApprovedSubject subject, int invoiceLineNumber, ReceiptFact receipt, int quantity) {
        return jdbc.update(
                "insert into receipt_allocation (id, invoice_case_id, purchase_order_id, review_decision_id,"
                        + " review_snapshot_id, review_payload_hash, evidence_bundle_id, invoice_line_number,"
                        + " receipt_line_snapshot_id, receipt_id, receipt_line_id, purchase_order_line_id,"
                        + " receipt_line_version, confirmed_quantity_at_approval, allocated_quantity, created_at)"
                        + " values (?, ?, 'PO-1001', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                UUID.randomUUID(),
                caseId,
                subject.decisionId(),
                subject.snapshotId(),
                subject.payloadHash(),
                subject.bundleId(),
                invoiceLineNumber,
                receipt.id(),
                receipt.receiptId(),
                receipt.receiptLineId(),
                receipt.purchaseOrderLineId(),
                receipt.receiptLineVersion(),
                receipt.confirmedQuantity(),
                quantity);
    }

    private ApprovedSubject approvedSubject(UUID caseId) {
        UUID decisionId = jdbc.queryForObject(
                "select id from review_decision where invoice_case_id = ? and decision = 'APPROVED'",
                UUID.class,
                caseId);
        UUID snapshotId = jdbc.queryForObject(
                "select review_snapshot_id from payment_request where invoice_case_id = ?", UUID.class, caseId);
        UUID bundleId = jdbc.queryForObject(
                "select evidence_bundle_id from payment_request where invoice_case_id = ?", UUID.class, caseId);
        String payloadHash = jdbc.queryForObject(
                "select review_payload_hash from payment_request where invoice_case_id = ?", String.class, caseId);
        return new ApprovedSubject(decisionId, snapshotId, bundleId, payloadHash);
    }

    private ReceiptFact receiptFact(String receiptLineId) {
        return jdbc.queryForObject(
                "select id, receipt_id, receipt_line_id, purchase_order_line_id, receipt_line_version,"
                        + " confirmed_quantity from receipt_line_snapshot where receipt_line_id = ?",
                (rs, rowNum) -> new ReceiptFact(
                        rs.getObject("id", UUID.class),
                        rs.getString("receipt_id"),
                        rs.getString("receipt_line_id"),
                        rs.getString("purchase_order_line_id"),
                        rs.getLong("receipt_line_version"),
                        rs.getInt("confirmed_quantity")),
                receiptLineId);
    }

    private PurchasingPayloads reverseMultiRowPayload() {
        return new PurchasingPayloads()
                .snapshotVersion(5)
                .addLine("POL-1001-1", ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                .addReceipt("RCV-A-1001", "CONFIRMED", "2026-01-05", 2,
                        PurchasingPayloads.receiptLine("RCL-A-1001", 2, "POL-1001-1", 1000))
                .addReceipt("RCV-B-1001", "CONFIRMED", "2026-01-06", 2,
                        PurchasingPayloads.receiptLine("RCL-B-1001", 2, "POL-1001-1", 1000))
                .addReceipt("RCV-C-1001", "CONFIRMED", "2026-01-07", 2,
                        PurchasingPayloads.receiptLine("RCL-C-1001", 2, "POL-1001-1", 1000));
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

    private PurchasingPayloads sharedTwoLinePayload() {
        return new PurchasingPayloads()
                .snapshotVersion(5)
                .addLine("POL-1001-1", ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                .addReceipt("RCV-A-1001", "CONFIRMED", "2026-01-05", 2,
                        PurchasingPayloads.receiptLine("RCL-A-1001", 2, "POL-1001-1", 30))
                .addReceipt("RCV-B-1001", "CONFIRMED", "2026-01-06", 2,
                        PurchasingPayloads.receiptLine("RCL-B-1001", 2, "POL-1001-1", 30));
    }

    private PurchasingPayloads spareLinePayload() {
        return new PurchasingPayloads()
                .snapshotVersion(5)
                .addLine("POL-1001-1", ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                .addReceipt("RCV-A-1001", "CONFIRMED", "2026-01-05", 2,
                        PurchasingPayloads.receiptLine("RCL-A-1001", 2, "POL-1001-1", 100))
                .addReceipt("RCV-B-1001", "CONFIRMED", "2026-01-06", 2,
                        PurchasingPayloads.receiptLine("RCL-B-1001", 2, "POL-1001-1", 20));
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

    private record ApprovedSubject(UUID decisionId, UUID snapshotId, UUID bundleId, String payloadHash) {
    }

    private record ReceiptFact(
            UUID id,
            String receiptId,
            String receiptLineId,
            String purchaseOrderLineId,
            long receiptLineVersion,
            int confirmedQuantity) {
    }

    enum ApprovalInterceptorStage {
        DECISION,
        ALLOCATION,
        PAYMENT,
        OUTBOX,
        CASE_TRANSITION,
        AUDIT,
        IDEMPOTENCY
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
        private volatile Boolean externalFetchTransactionActive;

        void reset() {
            externalFetchReached = null;
            externalFetchResume = null;
            purchaseOrderLockedReached = null;
            purchaseOrderLockResume = null;
            failAt.set(null);
            externalFetchTransactionActive = null;
        }

        Boolean externalFetchSawActiveTransaction() {
            return externalFetchTransactionActive;
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
            externalFetchTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
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

        @Override
        public void beforeOutboxWritten(UUID caseId) {
            failIfStage(ApprovalInterceptorStage.OUTBOX);
        }

        @Override
        public void afterCaseTransitioned(UUID caseId) {
            failIfStage(ApprovalInterceptorStage.CASE_TRANSITION);
        }

        @Override
        public void afterAuditRecorded(UUID caseId) {
            failIfStage(ApprovalInterceptorStage.AUDIT);
        }

        @Override
        public void afterIdempotencyRecorded(UUID caseId) {
            failIfStage(ApprovalInterceptorStage.IDEMPOTENCY);
        }
    }
}
