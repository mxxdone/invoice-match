package com.invoicematch.core.purchasingreference.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Produces a deterministic canonical JSON representation of one external
 * purchase order aggregate and its SHA-256 hash. Key order is fixed by building
 * the tree explicitly and repeated facts are sorted by stable external id, so
 * two payloads hash equally exactly when they describe the same facts.
 */
@Component
public class SnapshotPayloadHasher {

    private static final Comparator<PurchaseOrderLineFacts> PO_LINE_ORDER =
            Comparator.comparing(PurchaseOrderLineFacts::purchaseOrderLineId);
    private static final Comparator<ReceiptLineFacts> RECEIPT_LINE_ORDER =
            Comparator.comparing(ReceiptLineFacts::receiptLineId);
    private static final Comparator<ReceiptFacts> RECEIPT_ORDER =
            Comparator.comparing(ReceiptFacts::receiptId);

    private final ObjectMapper mapper = new ObjectMapper();

    public String canonicalJson(PurchaseOrderAggregate aggregate) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("snapshotVersion", aggregate.snapshotVersion());
        root.put("purchaseOrderId", aggregate.purchaseOrderId().value());

        ObjectNode purchaseOrder = root.putObject("purchaseOrder");
        purchaseOrder.put("status", aggregate.purchaseOrder().status().name());
        purchaseOrder.put("version", aggregate.purchaseOrder().version());
        purchaseOrder.put("supplierId", aggregate.purchaseOrder().supplierId().value());
        purchaseOrder.put("supplierName", aggregate.purchaseOrder().supplierName());

        ArrayNode lines = purchaseOrder.putArray("lines");
        aggregate.purchaseOrder().lines().stream().sorted(PO_LINE_ORDER).forEach(line -> {
            ObjectNode node = lines.addObject();
            node.put("purchaseOrderLineId", line.purchaseOrderLineId());
            node.put("itemId", line.itemId());
            node.put("itemName", line.itemName());
            node.put("orderedQuantity", line.orderedQuantity().value());
            node.put("unitPrice", line.unitPrice().amount());
        });

        ArrayNode receipts = root.putArray("receipts");
        aggregate.receipts().stream().sorted(RECEIPT_ORDER).forEach(receipt -> {
            ObjectNode node = receipts.addObject();
            node.put("receiptId", receipt.receiptId());
            node.put("status", receipt.status().name());
            node.put("receiptDate", receipt.receiptDate().toString());
            node.put("version", receipt.version());
            ArrayNode receiptLines = node.putArray("lines");
            receipt.lines().stream().sorted(RECEIPT_LINE_ORDER).forEach(line -> {
                ObjectNode lineNode = receiptLines.addObject();
                lineNode.put("receiptLineId", line.receiptLineId());
                lineNode.put("version", line.version());
                lineNode.put("purchaseOrderLineId", line.purchaseOrderLineId());
                lineNode.put("confirmedQuantity", line.confirmedQuantity().value());
            });
        });

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical payload serialization failed", e);
        }
    }

    public String sha256Hex(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Convenience for tests and callers that only need the hash of an aggregate.
     */
    public CanonicalPayload canonicalize(PurchaseOrderAggregate aggregate) {
        String json = canonicalJson(aggregate);
        return new CanonicalPayload(json, sha256Hex(json));
    }

    public record CanonicalPayload(String json, String hash) {
    }
}
