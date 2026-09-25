package com.invoicematch.core.purchasingreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.invoicematch.core.support.PurchasingPayloads.receiptLine;

import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand;
import com.invoicematch.core.purchasingreference.domain.ExternalFactUnconfirmedException;
import com.invoicematch.core.purchasingreference.domain.ExternalReferenceMismatchException;
import com.invoicematch.core.purchasingreference.domain.ExternalSnapshotConflictException;
import com.invoicematch.core.purchasingreference.domain.RefreshOutcome;
import com.invoicematch.core.purchasingreference.domain.RefreshResult;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
import java.time.OffsetDateTime;
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
 * version and hash semantics end to end.
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
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanSnapshots() {
        jdbc.update("delete from receipt_line_snapshot");
        jdbc.update("delete from receipt_snapshot");
        jdbc.update("delete from purchase_order_line_snapshot");
        jdbc.update("delete from purchase_order_snapshot");
    }

    @Test
    void firstRefreshCreatesSnapshot() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());

        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.CREATED);
        assertThat(result.storedVersion()).isEqualTo(5);
        assertThat(snapshotVersion()).isEqualTo(5L);
        assertThat(count("purchase_order_line_snapshot")).isEqualTo(2);
        assertThat(count("receipt_snapshot")).isEqualTo(1);
        assertThat(count("receipt_line_snapshot")).isEqualTo(2);
        assertThat(confirmedQuantity("RCL-1001-1-1")).isEqualTo(60);
        assertThat(payloadHash()).isNotBlank();
    }

    @Test
    void newerVersionReplacesSnapshotWithoutLeavingStaleChildren() {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        service.refresh(command());

        STUB.respond(200, versionWithConfirmedQuantity(6, 70));
        RefreshResult result = service.refresh(command());

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.UPDATED);
        assertThat(result.storedVersion()).isEqualTo(6);
        assertThat(snapshotVersion()).isEqualTo(6L);
        assertThat(count("purchase_order_line_snapshot")).isEqualTo(2);
        assertThat(count("receipt_snapshot")).isEqualTo(1);
        assertThat(count("receipt_line_snapshot")).isEqualTo(2);
        assertThat(confirmedQuantity("RCL-1001-1-1")).isEqualTo(70);
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
        assertThat(count("receipt_line_snapshot")).isEqualTo(2);
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

    private long snapshotVersion() {
        return jdbc.queryForObject(
                "select external_version from purchase_order_snapshot where purchase_order_id = ?", Long.class, PO_ID);
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

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int snapshotCount() {
        return count("purchase_order_snapshot");
    }
}
