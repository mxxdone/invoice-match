package com.invoicematch.core.purchasingreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.invoicematch.core.support.PurchasingPayloads.receiptLine;

import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotReader;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand;
import com.invoicematch.core.purchasingreference.application.SnapshotPayloadHasher;
import com.invoicematch.core.purchasingreference.domain.ExternalFactUnconfirmedException;
import com.invoicematch.core.purchasingreference.domain.ExternalReferenceMismatchException;
import com.invoicematch.core.purchasingreference.domain.ExternalSnapshotConflictException;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full refresh path against a real PostgreSQL instance and a real
 * HTTP stub for the external purchasing system, proving the V2 snapshot,
 * version, hash, stable-identity and concurrency semantics end to end.
 */
class PurchasingReferenceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PO_ID = "PO-1001";
    private static final StubPurchasingServer STUB;

    static {
        try {
            STUB = new StubPurchasingServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
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
    private SnapshotPayloadHasher hasher;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanSnapshots() {
        jdbc.update("delete from receipt_line_snapshot");
        jdbc.update("delete from receipt_snapshot");
        jdbc.update("delete from purchase_order_line_snapshot");
        jdbc.update("delete from purchase_order_snapshot");
    }

    @Test
    void firstRefreshCreatesSnapshotWithSeparateVersions() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());

        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.CREATED);
        assertThat(result.storedVersion()).isEqualTo(5);
        assertThat(snapshotVersion()).isEqualTo(5L);
        assertThat(purchaseOrderVersion()).isEqualTo(3L);
        assertThat(receiptVersion("RCV-1001-1")).isEqualTo(2L);
        assertThat(receiptLineVersion("RCL-1001-1-1")).isEqualTo(2L);
        assertThat(activeCount("purchase_order_line_snapshot")).isEqualTo(2);
        assertThat(activeCount("receipt_snapshot")).isEqualTo(1);
        assertThat(activeCount("receipt_line_snapshot")).isEqualTo(2);
        assertThat(confirmedQuantity("RCL-1001-1-1")).isEqualTo(60);
        assertThat(payloadHash()).isNotBlank();
    }

    @Test
    void newerVersionUpdatesInPlaceWithoutLeavingStaleChildren() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());

        STUB.respond(200, versionWithConfirmedQuantity(6, 70));
        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.UPDATED);
        assertThat(result.storedVersion()).isEqualTo(6);
        assertThat(snapshotVersion()).isEqualTo(6L);
        assertThat(activeCount("purchase_order_line_snapshot")).isEqualTo(2);
        assertThat(activeCount("receipt_snapshot")).isEqualTo(1);
        assertThat(activeCount("receipt_line_snapshot")).isEqualTo(2);
        assertThat(confirmedQuantity("RCL-1001-1-1")).isEqualTo(70);
    }

    @Test
    void retainedRowsKeepUuidAndRemovedRowsAreDeactivatedNotDeleted() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());

        UUID line1Id = uuidOf("purchase_order_line_snapshot", "purchase_order_line_id", "POL-1001-1");
        UUID line2Id = uuidOf("purchase_order_line_snapshot", "purchase_order_line_id", "POL-1001-2");
        UUID receiptId = uuidOf("receipt_snapshot", "receipt_id", "RCV-1001-1");
        UUID receiptLine1Id = uuidOf("receipt_line_snapshot", "receipt_line_id", "RCL-1001-1-1");
        UUID receiptLine2Id = uuidOf("receipt_line_snapshot", "receipt_line_id", "RCL-1001-1-2");

        STUB.respond(200, versionDroppingSecondLineAndReceiptLine().toJson());
        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.UPDATED);
        assertThat(uuidOf("purchase_order_line_snapshot", "purchase_order_line_id", "POL-1001-1"))
                .isEqualTo(line1Id);
        assertThat(uuidOf("receipt_snapshot", "receipt_id", "RCV-1001-1")).isEqualTo(receiptId);
        assertThat(uuidOf("receipt_line_snapshot", "receipt_line_id", "RCL-1001-1-1"))
                .isEqualTo(receiptLine1Id);

        assertThat(uuidOf("purchase_order_line_snapshot", "purchase_order_line_id", "POL-1001-2"))
                .isEqualTo(line2Id);
        assertThat(uuidOf("receipt_line_snapshot", "receipt_line_id", "RCL-1001-1-2"))
                .isEqualTo(receiptLine2Id);
        assertThat(active("purchase_order_line_snapshot", "purchase_order_line_id", "POL-1001-2"))
                .isFalse();
        assertThat(active("receipt_line_snapshot", "receipt_line_id", "RCL-1001-1-2"))
                .isFalse();
        assertThat(count("purchase_order_line_snapshot")).isEqualTo(2);
        assertThat(count("receipt_line_snapshot")).isEqualTo(2);

        PurchaseOrderAggregate current = reader.findCurrent(command().purchaseOrderId()).orElseThrow();
        assertThat(current.snapshotVersion()).isEqualTo(6);
        assertThat(current.purchaseOrder().version()).isEqualTo(4);
        assertThat(current.purchaseOrder().lines()).hasSize(1);
        assertThat(current.receipts()).hasSize(1);
        assertThat(current.receipts().get(0).lines()).hasSize(1);
        assertThat(current.receipts().get(0).lines().get(0).receiptLineId()).isEqualTo("RCL-1001-1-1");
        assertThat(current.receipts().get(0).lines().get(0).confirmedQuantity().value()).isEqualTo(70);
    }

    @Test
    void readerReturnsEmptyWhenNoSnapshotExists() {
        assertThat(reader.findCurrent(command().purchaseOrderId())).isEmpty();
    }

    @Test
    void receiptLineReferenceChangeUpdatesInPlaceAndKeepsCanonicalConsistency() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());
        UUID receiptLine1Id = uuidOf("receipt_line_snapshot", "receipt_line_id", "RCL-1001-1-1");

        STUB.respond(200, swappedReceiptLineReferences().toJson());
        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.UPDATED);
        assertThat(uuidOf("receipt_line_snapshot", "receipt_line_id", "RCL-1001-1-1"))
                .isEqualTo(receiptLine1Id);
        assertThat(receiptLinePurchaseOrderLineId("RCL-1001-1-1")).isEqualTo("POL-1001-2");
        assertThat(receiptLinePurchaseOrderLineId("RCL-1001-1-2")).isEqualTo("POL-1001-1");

        PurchaseOrderAggregate current = reader.findCurrent(command().purchaseOrderId()).orElseThrow();
        ReceiptLineFacts firstLine = current.receipts().get(0).lines().stream()
                .filter(line -> line.receiptLineId().equals("RCL-1001-1-1"))
                .findFirst()
                .orElseThrow();
        assertThat(firstLine.purchaseOrderLineId()).isEqualTo("POL-1001-2");
        assertThat(firstLine.confirmedQuantity().value()).isEqualTo(60);
        assertThat(hasher.canonicalize(current).hash()).isEqualTo(payloadHash());
    }

    @Test
    void olderVersionIsIgnoredAndDoesNotOverwrite() {
        STUB.respond(200, versionWithConfirmedQuantity(6, 70));
        service.refresh(command());

        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().snapshotVersion(5).toJson());
        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.STALE_IGNORED);
        assertThat(result.storedVersion()).isEqualTo(6);
        assertThat(snapshotVersion()).isEqualTo(6L);
        assertThat(confirmedQuantity("RCL-1001-1-1")).isEqualTo(70);
    }

    @Test
    void sameVersionSamePayloadIsIdempotentNoOp() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());
        String hashBefore = payloadHash();
        OffsetDateTime updatedBefore = updatedAt();

        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.UNCHANGED);
        assertThat(result.storedVersion()).isEqualTo(5);
        assertThat(payloadHash()).isEqualTo(hashBefore);
        assertThat(updatedAt()).isEqualTo(updatedBefore);
    }

    @Test
    void sameVersionDifferentPayloadIsConflictWithoutPartialChanges() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());
        String hashBefore = payloadHash();
        OffsetDateTime updatedBefore = updatedAt();

        STUB.respond(200, versionWithConfirmedQuantity(5, 99));
        assertThatThrownBy(() -> service.refresh(command()))
                .isInstanceOf(ExternalSnapshotConflictException.class);

        assertThat(snapshotVersion()).isEqualTo(5L);
        assertThat(payloadHash()).isEqualTo(hashBefore);
        assertThat(updatedAt()).isEqualTo(updatedBefore);
        assertThat(confirmedQuantity("RCL-1001-1-1")).isEqualTo(60);
        assertThat(activeCount("receipt_line_snapshot")).isEqualTo(2);
    }

    @Test
    void rejectedUnconfirmedFactPersistsNothing() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().status("UNCONFIRMED").toJson());

        assertThatThrownBy(() -> service.refresh(command()))
                .isInstanceOf(ExternalFactUnconfirmedException.class);

        assertThat(snapshotCount()).isZero();
    }

    @Test
    void rejectedChildReferencePersistsNothing() {
        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .clearReceipts()
                        .addReceipt(
                                "RCV-BAD",
                                "CONFIRMED",
                                "2026-01-06",
                                1,
                                receiptLine("RCL-BAD", 1, "POL-MISSING", 10))
                        .toJson());

        assertThatThrownBy(() -> service.refresh(command()))
                .isInstanceOf(ExternalReferenceMismatchException.class);

        assertThat(snapshotCount()).isZero();
        assertThat(count("receipt_line_snapshot")).isZero();
    }

    @Test
    void concurrentFirstRefreshIsSerializedWithoutIntegrityLeak() throws Exception {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try {
            List<Future<RefreshResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.refresh(command());
                }));
            }

            List<RefreshOutcome> outcomes = new ArrayList<>();
            for (Future<RefreshResult> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS).outcome());
            }

            assertThat(snapshotCount()).isEqualTo(1);
            assertThat(activeCount("receipt_line_snapshot")).isEqualTo(2);
            assertThat(outcomes)
                    .allSatisfy(outcome -> assertThat(outcome)
                            .isIn(RefreshOutcome.CREATED, RefreshOutcome.UNCHANGED));
            assertThat(outcomes).contains(RefreshOutcome.CREATED);
        } finally {
            pool.shutdownNow();
        }
    }

    private RefreshPurchaseOrderCommand command() {
        return new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of("SUP-1"));
    }

    private static String versionWithConfirmedQuantity(long snapshotVersion, int confirmedQuantity) {
        return PurchasingPayloads.confirmedPartialReceipt()
                .snapshotVersion(snapshotVersion)
                .clearReceipts()
                .addReceipt(
                        "RCV-1001-1",
                        "CONFIRMED",
                        "2026-01-05",
                        2,
                        receiptLine("RCL-1001-1-1", 2, "POL-1001-1", confirmedQuantity),
                        receiptLine("RCL-1001-1-2", 2, "POL-1001-2", 20))
                .toJson();
    }

    private static PurchasingPayloads swappedReceiptLineReferences() {
        return PurchasingPayloads.confirmedPartialReceipt()
                .snapshotVersion(6)
                .purchaseOrderVersion(4)
                .clearReceipts()
                .addReceipt(
                        "RCV-1001-1",
                        "CONFIRMED",
                        "2026-01-05",
                        3,
                        receiptLine("RCL-1001-1-1", 3, "POL-1001-2", 60),
                        receiptLine("RCL-1001-1-2", 3, "POL-1001-1", 20));
    }

    private static PurchasingPayloads versionDroppingSecondLineAndReceiptLine() {        return PurchasingPayloads.confirmedPartialReceipt()
                .snapshotVersion(6)
                .purchaseOrderVersion(4)
                .clearLines()
                .addLine("POL-1001-1", "ITEM-A4-80", "Premium Copy Paper A4 80g", 100, 2500)
                .clearReceipts()
                .addReceipt(
                        "RCV-1001-1",
                        "CONFIRMED",
                        "2026-01-05",
                        2,
                        receiptLine("RCL-1001-1-1", 2, "POL-1001-1", 70));
    }

    private long snapshotVersion() {
        return jdbc.queryForObject(
                "select snapshot_version from purchase_order_snapshot where purchase_order_id = ?", Long.class, PO_ID);
    }

    private long purchaseOrderVersion() {
        return jdbc.queryForObject(
                "select purchase_order_version from purchase_order_snapshot where purchase_order_id = ?",
                Long.class,
                PO_ID);
    }

    private long receiptVersion(String receiptId) {
        return jdbc.queryForObject(
                "select receipt_version from receipt_snapshot where receipt_id = ?", Long.class, receiptId);
    }

    private long receiptLineVersion(String receiptLineId) {
        return jdbc.queryForObject(
                "select receipt_line_version from receipt_line_snapshot where receipt_line_id = ?",
                Long.class,
                receiptLineId);
    }

    private String payloadHash() {
        return jdbc.queryForObject(
                "select payload_hash from purchase_order_snapshot where purchase_order_id = ?", String.class, PO_ID);
    }

    private OffsetDateTime updatedAt() {
        return jdbc.queryForObject(
                "select updated_at from purchase_order_snapshot where purchase_order_id = ?",
                OffsetDateTime.class,
                PO_ID);
    }

    private int confirmedQuantity(String receiptLineId) {
        return jdbc.queryForObject(
                "select confirmed_quantity from receipt_line_snapshot where receipt_line_id = ?",
                Integer.class,
                receiptLineId);
    }

    private String receiptLinePurchaseOrderLineId(String receiptLineId) {
        return jdbc.queryForObject(
                "select purchase_order_line_id from receipt_line_snapshot where receipt_line_id = ?",
                String.class,
                receiptLineId);
    }

    private UUID uuidOf(String table, String keyColumn, String key) {
        return jdbc.queryForObject(
                "select id from " + table + " where " + keyColumn + " = ?", UUID.class, key);
    }

    private boolean active(String table, String keyColumn, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select active from " + table + " where " + keyColumn + " = ?", Boolean.class, key));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int activeCount(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where active", Integer.class);
    }

    private int snapshotCount() {
        return count("purchase_order_snapshot");
    }
}
