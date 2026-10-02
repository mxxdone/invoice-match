package com.invoicematch.core.invoicecase.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.document.domain.DocumentEvidence;
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
 * Builds the deterministic canonical JSON of a submitted evidence bundle and
 * its SHA-256 hash. The tree is built explicitly and lines are sorted by line
 * number, so two submissions with the same case header, revision and lines hash
 * equally regardless of collection order.
 *
 * <p>A submission with no completed documents produces the unchanged legacy
 * payload: the case header, revision and lines only. A submission that froze
 * completed documents adds an explicit {@code schemaVersion: 2} and a
 * {@code documents} array sorted by document id, so the document metadata is
 * deterministic and order-independent. Each document records the document id,
 * its original draft revision, name, media type, size and SHA-256 in a fixed
 * field order; object keys and upload URLs are never part of the payload.
 */
@Component
public class EvidenceBundlePayloadHasher {

    /** Persisted schema of a document-less legacy payload (unchanged bytes). */
    public static final String LEGACY_SCHEMA = "legacy-v1";

    /** Persisted schema of a payload that froze completed documents. */
    public static final String DOCUMENT_SCHEMA = "document-v2";

    /** JSON {@code schemaVersion} of a document-bearing payload. */
    public static final int DOCUMENT_SCHEMA_VERSION = 2;

    private static final Comparator<InvoiceLine> LINE_ORDER = Comparator.comparingInt(InvoiceLine::lineNumber);
    private static final Comparator<DocumentEvidence> DOCUMENT_ORDER =
            Comparator.comparing(document -> document.documentId().toString());

    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CanonicalPayload canonicalize(InvoiceCase invoiceCase, int revisionNumber, List<InvoiceLine> lines) {
        return canonicalize(invoiceCase, revisionNumber, lines, List.of());
    }

    public CanonicalPayload canonicalize(
            InvoiceCase invoiceCase, int revisionNumber, List<InvoiceLine> lines, List<DocumentEvidence> documents) {
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

        if (documents != null && !documents.isEmpty()) {
            root.put("schemaVersion", DOCUMENT_SCHEMA_VERSION);
            ArrayNode documentArray = root.putArray("documents");
            documents.stream().sorted(DOCUMENT_ORDER).forEach(document -> {
                ObjectNode node = documentArray.addObject();
                node.put("documentId", document.documentId().toString());
                node.put("sourceDraftRevisionId", document.sourceDraftRevisionId().toString());
                node.put("fileName", document.fileName());
                node.put("mediaType", document.mediaType());
                node.put("sizeBytes", document.sizeBytes());
                node.put("checksum", document.checksum());
            });
        }

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
