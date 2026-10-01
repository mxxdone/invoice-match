package com.invoicematch.core.matching.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.matching.domain.MatchException;
import com.invoicematch.core.matching.domain.MatchLineOutcome;
import com.invoicematch.core.matching.domain.MatchPoLine;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Focused canonical encoder for one deterministic match computation. It owns
 * only the JSON shape and the SHA-256 hash; the matching calculation lives in
 * {@link MatchEngine}.
 *
 * <p>It preserves the deterministic collection order the engine already
 * established (sorted invoice lines, the mapping index, typed line outcomes,
 * ordered exceptions, receipt ordering) and adds no new sorting of its own, so
 * the canonical bytes and hash are unchanged from the pre-refactor engine.
 *
 * <p>This module has no dependency on persistence or application orchestration
 * and deliberately keeps its own default {@link ObjectMapper}: the canonical
 * bytes must not change if the Spring global mapper is reconfigured.
 */
final class MatchResultPayloadEncoder {

    /** Stable identifier of the canonical payload shape, bumped on any change. */
    static final String SCHEMA_VERSION = "match-result-v3";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Comparator<ReceiptFacts> RECEIPT_ORDER = Comparator.comparing(ReceiptFacts::receiptId);
    private static final Comparator<ReceiptLineFacts> RECEIPT_FACT_LINE_ORDER =
            Comparator.comparing(ReceiptLineFacts::receiptLineId);

    private MatchResultPayloadEncoder() {
    }

    static Encoded encode(
            MatchInput input,
            List<EvidenceBundlePayload.EvidenceLine> invoiceLines,
            Map<Integer, AppliedMapping> mappingsByLine,
            List<MatchLineOutcome> outcomes,
            List<MatchException> exceptions,
            boolean normal) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("caseId", input.caseId().toString());
        root.put("supplierId", input.supplierId());
        root.put("purchaseOrderId", input.purchaseOrderId());
        root.put("invoiceNumber", input.invoiceNumber());
        root.put("normalizedInvoiceNumber", input.normalizedInvoiceNumber());
        root.put("caseVersion", input.caseVersion());

        ObjectNode bundle = root.putObject("evidenceBundle");
        bundle.put("id", input.evidenceBundleId().toString());
        bundle.put("version", input.evidenceBundleVersion());
        bundle.put("payloadHash", input.evidenceBundleHash());

        // Effective case-local mappings actually applied to a line present in
        // this bundle, sorted by line number. Each carries the exact purchase
        // order line the human chose, so re-matches cannot silently retarget.
        ArrayNode appliedMappings = root.putArray("appliedMappings");
        for (EvidenceBundlePayload.EvidenceLine line : invoiceLines) {
            AppliedMapping mapping = mappingsByLine.get(line.lineNumber());
            if (mapping != null) {
                ObjectNode node = appliedMappings.addObject();
                node.put("lineNumber", line.lineNumber());
                node.put("itemId", mapping.itemId());
                node.put("purchaseOrderLineId", mapping.purchaseOrderLineId());
            }
        }

        ObjectNode purchasing = root.putObject("purchasingSnapshot");
        purchasing.put("snapshotVersion", input.purchasing().snapshotVersion());
        purchasing.put("purchaseOrderVersion", input.purchasing().purchaseOrder().version());
        purchasing.put("payloadHash", input.purchasingSnapshotHash());
        ArrayNode receipts = purchasing.putArray("receipts");
        input.purchasing().receipts().stream()
                .filter(receipt -> receipt.status() == ReceiptStatus.CONFIRMED)
                .sorted(RECEIPT_ORDER)
                .forEach(receipt -> writeReceipt(receipts, receipt));

        ObjectNode allocationPlan = root.putObject("allocationPlan");
        allocationPlan.put("consuming", false);
        allocationPlan.put("mode", "NON_CONSUMING_EXPECTED_PLAN_V1");
        allocationPlan.put("fifoOrdering", "receiptDate,receiptLineId,receiptId");

        ArrayNode lineOutcomes = root.putArray("lineOutcomes");
        outcomes.forEach(outcome -> writeLineOutcome(lineOutcomes, outcome));

        ArrayNode exceptionsNode = root.putArray("exceptions");
        exceptions.forEach(exception -> writeException(exceptionsNode, exception));

        root.put("normal", normal);
        String json = write(root);
        return new Encoded(json, sha256Hex(json));
    }

    private static void writeReceipt(ArrayNode receipts, ReceiptFacts receipt) {
        ObjectNode node = receipts.addObject();
        node.put("receiptId", receipt.receiptId());
        node.put("status", receipt.status().name());
        node.put("receiptDate", receipt.receiptDate().toString());
        node.put("version", receipt.version());
        ArrayNode lines = node.putArray("lines");
        receipt.lines().stream().sorted(RECEIPT_FACT_LINE_ORDER).forEach(line -> {
            ObjectNode lineNode = lines.addObject();
            lineNode.put("receiptLineId", line.receiptLineId());
            lineNode.put("version", line.version());
            lineNode.put("purchaseOrderLineId", line.purchaseOrderLineId());
            lineNode.put("confirmedQuantity", line.confirmedQuantity().value());
        });
    }

    private static void writeLineOutcome(ArrayNode lineOutcomes, MatchLineOutcome outcome) {
        ObjectNode node = lineOutcomes.addObject();
        node.put("lineNumber", outcome.lineNumber());
        node.put("rawItemName", outcome.rawItemName());
        node.put("confirmedItemId", outcome.confirmedItemId());
        node.put("status", outcome.status().name());

        ArrayNode candidateIds = node.putArray("candidatePoLineIds");
        outcome.candidatePoLineIds().forEach(candidateIds::add);

        if (outcome.purchaseOrderLine() == null) {
            node.putNull("purchaseOrderLine");
        } else {
            MatchPoLine poLine = outcome.purchaseOrderLine();
            ObjectNode poLineNode = node.putObject("purchaseOrderLine");
            poLineNode.put("purchaseOrderLineId", poLine.purchaseOrderLineId());
            poLineNode.put("itemId", poLine.itemId());
            poLineNode.put("orderedQuantity", poLine.orderedQuantity());
            poLineNode.put("unitPrice", poLine.unitPrice());
        }

        node.put("invoiceQuantity", outcome.invoiceQuantity());
        node.put("invoiceUnitPrice", outcome.invoiceUnitPrice());
        node.put("availableConfirmedQuantity", outcome.availableConfirmedQuantity());
        node.put("plannedQuantity", outcome.plannedQuantity());

        ArrayNode plan = node.putArray("expectedAllocationPlan");
        outcome.expectedAllocationPlan().forEach(allocation -> {
            ObjectNode allocationNode = plan.addObject();
            allocationNode.put("receiptId", allocation.receiptId());
            allocationNode.put("receiptLineId", allocation.receiptLineId());
            allocationNode.put("receiptDate", allocation.receiptDate().toString());
            allocationNode.put("receiptLineVersion", allocation.receiptLineVersion());
            allocationNode.put("confirmedQuantity", allocation.confirmedQuantity());
            allocationNode.put("plannedQuantity", allocation.plannedQuantity());
        });

        ArrayNode lineExceptions = node.putArray("exceptions");
        outcome.exceptions().forEach(exception -> writeException(lineExceptions, exception));
    }

    private static void writeException(ArrayNode target, MatchException exception) {
        ObjectNode node = target.addObject();
        node.put("type", exception.type().name());
        if (exception.lineNumber() == null) {
            node.putNull("lineNumber");
        } else {
            node.put("lineNumber", exception.lineNumber());
        }
        node.set("details", MAPPER.valueToTree(exception.details()));
    }

    private static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical match payload serialization failed", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The canonical JSON bytes and their SHA-256 hash for one match. */
    record Encoded(String json, String hash) {
    }
}
