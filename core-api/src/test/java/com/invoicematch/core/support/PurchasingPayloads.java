package com.invoicematch.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds external purchasing aggregate JSON payloads for tests. The default
 * fixture mirrors the deterministic mock-purchasing {@code PO-1001} aggregate:
 * a confirmed purchase order with a confirmed partial receipt.
 */
public final class PurchasingPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private long snapshotVersion = 5;
    private String purchaseOrderId = "PO-1001";
    private String status = "CONFIRMED";
    private long purchaseOrderVersion = 3;
    private String supplierId = "SUP-1";
    private String supplierName = "Hanul Office Supply";
    private final List<ObjectNode> lines = new ArrayList<>();
    private final List<ObjectNode> receipts = new ArrayList<>();

    public static PurchasingPayloads confirmedPartialReceipt() {
        return new PurchasingPayloads()
                .addLine("POL-1001-1", "ITEM-A4-80", "Premium Copy Paper A4 80g", 100, 2500)
                .addLine("POL-1001-2", "ITEM-TONER-BK", "Laser Toner Black", 20, 55000)
                .addReceipt(
                        "RCV-1001-1",
                        "CONFIRMED",
                        "2026-01-05",
                        2,
                        receiptLine("RCL-1001-1-1", 2, "POL-1001-1", 60),
                        receiptLine("RCL-1001-1-2", 2, "POL-1001-2", 20));
    }

    public static ObjectNode receiptLine(String receiptLineId, long version, String purchaseOrderLineId, int qty) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("receiptLineId", receiptLineId);
        node.put("version", version);
        node.put("purchaseOrderLineId", purchaseOrderLineId);
        node.put("confirmedQuantity", qty);
        return node;
    }

    public PurchasingPayloads snapshotVersion(long value) {
        this.snapshotVersion = value;
        return this;
    }

    public PurchasingPayloads purchaseOrderId(String value) {
        this.purchaseOrderId = value;
        return this;
    }

    public PurchasingPayloads status(String value) {
        this.status = value;
        return this;
    }

    public PurchasingPayloads purchaseOrderVersion(long value) {
        this.purchaseOrderVersion = value;
        return this;
    }

    public PurchasingPayloads supplierId(String value) {
        this.supplierId = value;
        return this;
    }

    public PurchasingPayloads supplierName(String value) {
        this.supplierName = value;
        return this;
    }

    public PurchasingPayloads addLine(
            String purchaseOrderLineId, String itemId, String itemName, int orderedQuantity, long unitPrice) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("purchaseOrderLineId", purchaseOrderLineId);
        node.put("itemId", itemId);
        node.put("itemName", itemName);
        node.put("orderedQuantity", orderedQuantity);
        node.put("unitPrice", unitPrice);
        lines.add(node);
        return this;
    }

    public PurchasingPayloads addReceipt(
            String receiptId, String receiptStatus, String receiptDate, long version, ObjectNode... receiptLines) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("receiptId", receiptId);
        node.put("status", receiptStatus);
        node.put("receiptDate", receiptDate);
        node.put("version", version);
        ArrayNode array = node.putArray("lines");
        for (ObjectNode line : receiptLines) {
            array.add(line);
        }
        receipts.add(node);
        return this;
    }

    public PurchasingPayloads clearLines() {
        lines.clear();
        return this;
    }

    public PurchasingPayloads clearReceipts() {
        receipts.clear();
        return this;
    }

    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("snapshotVersion", snapshotVersion);

        ObjectNode purchaseOrder = root.putObject("purchaseOrder");
        purchaseOrder.put("purchaseOrderId", purchaseOrderId);
        purchaseOrder.put("status", status);
        purchaseOrder.put("version", purchaseOrderVersion);
        ObjectNode supplier = purchaseOrder.putObject("supplier");
        supplier.put("supplierId", supplierId);
        supplier.put("name", supplierName);
        ArrayNode lineArray = purchaseOrder.putArray("lines");
        lines.forEach(lineArray::add);

        ArrayNode receiptArray = root.putArray("receipts");
        receipts.forEach(receiptArray::add);

        return root.toString();
    }
}
