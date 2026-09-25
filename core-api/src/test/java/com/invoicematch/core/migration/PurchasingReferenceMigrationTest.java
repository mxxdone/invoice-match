package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the P1-02 Flyway V2 migration applies to a real PostgreSQL instance
 * and that the purchasing reference snapshot constraints and version columns
 * hold.
 */
class PurchasingReferenceMigrationTest extends AbstractPostgresIntegrationTest {

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
    void appliesV2MigrationToRealPostgreSql() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tables)
                .contains(
                        "purchase_order_snapshot",
                        "purchase_order_line_snapshot",
                        "receipt_snapshot",
                        "receipt_line_snapshot");

        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void exposesSeparateQueryableVersionColumns() {
        seedSnapshot("PO-1");
        seedLine("PO-1", "POL-1");
        seedReceipt("PO-1", "RCV-1");
        insertReceiptLine("PO-1", "RCV-1", "RCL-1", "POL-1", 0);

        assertThat(jdbc.queryForObject(
                        "select snapshot_version from purchase_order_snapshot where purchase_order_id = 'PO-1'",
                        Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select purchase_order_version from purchase_order_snapshot where purchase_order_id = 'PO-1'",
                        Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select receipt_version from receipt_snapshot where receipt_id = 'RCV-1'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select receipt_line_version from receipt_line_snapshot where receipt_line_id = 'RCL-1'",
                        Long.class))
                .isEqualTo(1L);
    }

    @Test
    void activeDefaultsToTrue() {
        seedSnapshot("PO-ACTIVE");
        seedLine("PO-ACTIVE", "POL-1");
        seedReceipt("PO-ACTIVE", "RCV-1");
        insertReceiptLine("PO-ACTIVE", "RCV-1", "RCL-1", "POL-1", 1);

        for (String table : List.of(
                "purchase_order_line_snapshot", "receipt_snapshot", "receipt_line_snapshot")) {
            Map<String, Object> row = jdbc.queryForMap("select active from " + table + " limit 1");
            assertThat(row).containsEntry("active", true);
        }
    }

    @Test
    void allowsZeroConfirmedQuantityAndRejectsNegativeOne() {
        seedSnapshot("PO-1");
        seedLine("PO-1", "POL-1");
        seedReceipt("PO-1", "RCV-1");

        insertReceiptLine("PO-1", "RCV-1", "RCL-ZERO", "POL-1", 0);
        assertThat(jdbc.queryForObject(
                        "select confirmed_quantity from receipt_line_snapshot where receipt_line_id = 'RCL-ZERO'",
                        Integer.class))
                .isZero();

        assertThatThrownBy(() -> insertReceiptLine("PO-1", "RCV-1", "RCL-NEG", "POL-1", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeSnapshotVersion() {
        assertThatThrownBy(() -> insertSnapshot("PO-NEG-SNAPSHOT", -1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativePurchaseOrderVersion() {
        assertThatThrownBy(() -> insertSnapshot("PO-NEG-PO", 1, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeReceiptLineVersion() {
        seedSnapshot("PO-2");
        seedLine("PO-2", "POL-1");
        seedReceipt("PO-2", "RCV-1");

        assertThatThrownBy(() -> jdbc.update(
                        "insert into receipt_line_snapshot (id, purchase_order_id, receipt_id, receipt_line_id, "
                                + "purchase_order_line_id, receipt_line_version, confirmed_quantity) "
                                + "values (?, 'PO-2', 'RCV-1', 'RCL-NEG', 'POL-1', -1, 1)",
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonPositiveOrderedQuantity() {
        seedSnapshot("PO-3");

        assertThatThrownBy(() -> seedLine("PO-3", "POL-BAD", 0, 1000))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateExternalPurchaseOrderLine() {
        seedSnapshot("PO-4");
        seedLine("PO-4", "POL-1");

        assertThatThrownBy(() -> seedLine("PO-4", "POL-1")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReceiptLineReferencingUnknownPurchaseOrderLine() {
        seedSnapshot("PO-5");
        seedReceipt("PO-5", "RCV-1");

        assertThatThrownBy(() -> insertReceiptLine("PO-5", "RCV-1", "RCL-1", "POL-MISSING", 5))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReceiptLineFromAnotherPurchaseOrdersReceipt() {
        seedSnapshot("PO-6");
        seedSnapshot("PO-7");
        seedLine("PO-6", "POL-6");
        seedReceipt("PO-7", "RCV-7");

        assertThatThrownBy(() -> insertReceiptLine("PO-6", "RCV-7", "RCL-1", "POL-6", 5))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void seedSnapshot(String purchaseOrderId) {
        insertSnapshot(purchaseOrderId, 1, 1);
    }

    private void insertSnapshot(String purchaseOrderId, long snapshotVersion, long purchaseOrderVersion) {
        jdbc.update(
                "insert into purchase_order_snapshot (purchase_order_id, supplier_id, supplier_name, status, "
                        + "snapshot_version, purchase_order_version, payload_hash, payload, retrieved_at, updated_at) "
                        + "values (?, 'SUP-1', 'Supplier', 'CONFIRMED', ?, ?, 'hash', '{}'::jsonb, now(), now())",
                purchaseOrderId,
                snapshotVersion,
                purchaseOrderVersion);
    }

    private UUID seedLine(String purchaseOrderId, String purchaseOrderLineId) {
        return seedLine(purchaseOrderId, purchaseOrderLineId, 10, 1000);
    }

    private UUID seedLine(String purchaseOrderId, String purchaseOrderLineId, int orderedQuantity, long unitPrice) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into purchase_order_line_snapshot (id, purchase_order_id, purchase_order_line_id, item_id, "
                        + "item_name, ordered_quantity, unit_price) values (?, ?, ?, 'ITEM-1', 'Item', ?, ?)",
                id,
                purchaseOrderId,
                purchaseOrderLineId,
                orderedQuantity,
                unitPrice);
        return id;
    }

    private UUID seedReceipt(String purchaseOrderId, String receiptId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into receipt_snapshot (id, purchase_order_id, receipt_id, status, receipt_date, "
                        + "receipt_version) values (?, ?, ?, 'CONFIRMED', date '2026-01-05', 1)",
                id,
                purchaseOrderId,
                receiptId);
        return id;
    }

    private void insertReceiptLine(
            String purchaseOrderId, String receiptId, String receiptLineId, String purchaseOrderLineId, int quantity) {
        jdbc.update(
                "insert into receipt_line_snapshot (id, purchase_order_id, receipt_id, receipt_line_id, "
                        + "purchase_order_line_id, receipt_line_version, confirmed_quantity) "
                        + "values (?, ?, ?, ?, ?, 1, ?)",
                UUID.randomUUID(),
                purchaseOrderId,
                receiptId,
                receiptLineId,
                purchaseOrderLineId,
                quantity);
    }
}
