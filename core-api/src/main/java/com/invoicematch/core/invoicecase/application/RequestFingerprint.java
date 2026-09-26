package com.invoicematch.core.invoicecase.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Canonical SHA-256 fingerprint of the business payload of a write request,
 * excluding the request id itself. Two requests with the same request id and
 * the same fingerprint are replays; a different fingerprint is a conflict.
 * Collection inputs are sorted so client ordering does not change identity.
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper mapper = new ObjectMapper();

    public String create(CreateInvoiceCaseCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("supplierId", command.supplierId());
        node.put("purchaseOrderId", command.purchaseOrderId());
        node.put("invoiceNumber", command.invoiceNumber());
        return sha256Hex(write(node));
    }

    public String replaceDraft(ReplaceDraftLinesCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        ArrayNode lines = node.putArray("lines");
        command.lines().stream()
                .sorted(Comparator.comparingInt(InvoiceLineInput::lineNumber))
                .forEach(line -> {
                    ObjectNode lineNode = lines.addObject();
                    lineNode.put("lineNumber", line.lineNumber());
                    lineNode.put("rawItemName", line.rawItemName());
                    lineNode.put("quantity", line.quantity());
                    lineNode.put("unitPrice", line.unitPrice());
                    lineNode.put("confirmedItemId", line.confirmedItemId());
                });
        return sha256Hex(write(node));
    }

    public String submit(SubmitInvoiceCaseCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        return sha256Hex(write(node));
    }

    public String openRevision(OpenSupplementRevisionCommand command) {
        ObjectNode node = mapper.createObjectNode();
        node.put("expectedCaseVersion", command.expectedCaseVersion());
        return sha256Hex(write(node));
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Request fingerprint serialization failed", e);
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
}
