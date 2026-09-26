package com.invoicematch.core.invoicecase.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the deterministic canonical JSON of a submitted manual claim and its
 * SHA-256 hash. The tree is built explicitly and lines are sorted by line
 * number, so two submissions with the same case header, revision and lines hash
 * equally regardless of collection order.
 */
@Component
public class EvidenceBundlePayloadHasher {

    private static final Comparator<InvoiceLine> LINE_ORDER = Comparator.comparingInt(InvoiceLine::lineNumber);

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CanonicalPayload canonicalize(InvoiceCase invoiceCase, int revisionNumber, List<InvoiceLine> lines) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("caseId", invoiceCase.id().value().toString());
        root.put("supplierId", invoiceCase.supplier().value());
        root.put("purchaseOrderId", invoiceCase.purchaseOrder().value());
        root.put("invoiceNumber", invoiceCase.invoiceNumber());
        root.put("revisionNumber", revisionNumber);

        ArrayNode lineArray = root.putArray("lines");
        lines.stream().sorted(LINE_ORDER).forEach(line -> {
            ObjectNode node = lineArray.addObject();
            node.put("lineNumber", line.lineNumber());
            node.put("rawItemName", line.rawItemName());
            node.put("quantity", line.quantity().value());
            node.put("unitPrice", line.unitPrice().amount());
            node.put("confirmedItemId", line.confirmedItemId());
        });

        String json = write(root);
        return new CanonicalPayload(json, sha256Hex(json));
    }

    public EvidenceBundlePayload parse(String payload) {
        try {
            return mapper.readValue(payload, EvidenceBundlePayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored evidence bundle payload is not readable", e);
        }
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical payload serialization failed", e);
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

    public record CanonicalPayload(String json, String hash) {
    }
}
