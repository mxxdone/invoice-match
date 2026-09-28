package com.invoicematch.core.purchasingreference.application;

import static com.invoicematch.core.support.PurchasingPayloads.receiptLine;
import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.RefreshOutcome;
import com.invoicematch.core.purchasingreference.domain.RefreshResult;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that a current-snapshot read observes a single consistent PostgreSQL
 * snapshot even when a refresh commits between the root read and the child
 * reads. The read transaction is paused mid-flight via a package-private test
 * seam, a newer refresh is committed, and the returned aggregate must be
 * entirely the old version rather than a mix of old and new facts.
 */
class PurchasingReferenceIsolationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PO_ID = "PO-1001";
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
        SnapshotReadInterceptor snapshotReadInterceptor() {
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
    private PurchasingReferenceService service;

    @Autowired
    private PurchaseOrderSnapshotReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        INTERCEPTOR.reset();
        jdbc.execute("truncate table receipt_allocation");
        jdbc.update("delete from receipt_line_snapshot");
        jdbc.update("delete from receipt_snapshot");
        jdbc.update("delete from purchase_order_line_snapshot");
        jdbc.update("delete from purchase_order_snapshot");
    }

    @Test
    void readDoesNotMixOldAndNewSnapshotAcrossRefreshCommit() throws Exception {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());

        INTERCEPTOR.arm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PurchaseOrderAggregate> readFuture = pool.submit(
                    () -> reader.findCurrent(PurchaseOrderId.of(PO_ID)).orElseThrow());
            assertThat(INTERCEPTOR.awaitRootLoaded(10, TimeUnit.SECONDS)).isTrue();

            STUB.respond(200, newerVersionWithChangedFacts());
            RefreshResult updated = service.refresh(command());
            assertThat(updated.outcome()).isEqualTo(RefreshOutcome.UPDATED);
            assertThat(jdbc.queryForObject(
                            "select snapshot_version from purchase_order_snapshot where purchase_order_id = ?",
                            Long.class,
                            PO_ID))
                    .isEqualTo(6L);

            INTERCEPTOR.resume();

            PurchaseOrderAggregate aggregate = readFuture.get(20, TimeUnit.SECONDS);
            assertAllOld(aggregate);
        } finally {
            INTERCEPTOR.reset();
            pool.shutdownNow();
        }
    }

    @Test
    void readInsideCallerReadCommittedTransactionAlsoSeesConsistentSnapshot() throws Exception {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.setReadOnly(true);

        INTERCEPTOR.arm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PurchaseOrderAggregate> readFuture = pool.submit(
                    () -> outer.execute(status -> reader.findCurrent(PurchaseOrderId.of(PO_ID)).orElseThrow()));
            assertThat(INTERCEPTOR.awaitRootLoaded(10, TimeUnit.SECONDS)).isTrue();

            STUB.respond(200, newerVersionWithChangedFacts());
            RefreshResult updated = service.refresh(command());
            assertThat(updated.outcome()).isEqualTo(RefreshOutcome.UPDATED);

            INTERCEPTOR.resume();

            PurchaseOrderAggregate aggregate = readFuture.get(20, TimeUnit.SECONDS);
            assertAllOld(aggregate);
        } finally {
            INTERCEPTOR.reset();
            pool.shutdownNow();
        }
    }

    private static void assertAllOld(PurchaseOrderAggregate aggregate) {
        assertThat(aggregate.snapshotVersion()).isEqualTo(5);
        assertThat(aggregate.purchaseOrder().version()).isEqualTo(3);
        ReceiptLineFacts receiptLine = aggregate.receipts().get(0).lines().stream()
                .filter(line -> line.receiptLineId().equals("RCL-1001-1-1"))
                .findFirst()
                .orElseThrow();
        assertThat(receiptLine.purchaseOrderLineId()).isEqualTo("POL-1001-1");
        assertThat(receiptLine.confirmedQuantity().value()).isEqualTo(60);
        assertThat(receiptLine.version()).isEqualTo(2);
    }

    private static RefreshPurchaseOrderCommand command() {
        return new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of("SUP-1"));
    }

    private static String newerVersionWithChangedFacts() {
        return PurchasingPayloads.confirmedPartialReceipt()
                .snapshotVersion(6)
                .purchaseOrderVersion(4)
                .clearReceipts()
                .addReceipt(
                        "RCV-1001-1",
                        "CONFIRMED",
                        "2026-01-05",
                        3,
                        receiptLine("RCL-1001-1-1", 3, "POL-1001-2", 70),
                        receiptLine("RCL-1001-1-2", 3, "POL-1001-1", 20))
                .toJson();
    }

    /**
     * Blocks the read transaction after it has read the root row, until the test
     * resumes it.
     */
    static final class ControlledInterceptor implements SnapshotReadInterceptor {

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

        boolean awaitRootLoaded(long timeout, TimeUnit unit) throws InterruptedException {
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
        public void afterRootLoaded() {
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
