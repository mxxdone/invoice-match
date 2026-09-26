package com.invoicematch.core.review.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.matching.application.AppliedMapping;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.Quantity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Builds the canonical JSON of a frozen review snapshot and its SHA-256. The
 * payload contains exactly what a reviewer is shown: the case version, the
 * frozen evidence bundle id/version/hash, the source match result id/number/
 * hash and its full calculation evidence, the effective case-local mappings,
 * the invoice line amounts and total, and the captured purchasing snapshot
 * versions/hash.
 *
 * <p>Every collection is explicitly ordered, and generated identity/time
 * fields of the snapshot and of its decisions are excluded, so the same
 * semantic inputs hash equally regardless of repository or list ordering.
 * Amounts use checked arithmetic and never wrap silently.
 */
@Component
public class ReviewSnapshotPayloadBuilder {

    public static final String SCHEMA_VERSION = "review-snapshot-v1";

    private final ObjectMapper mapper = new ObjectMapper();

    public CanonicalPayload canonicalize(ReviewSnapshotPayloadInput input) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("caseId", input.caseId().toString());
        root.put("caseVersion", input.caseVersion());

        ObjectNode bundle = root.putObject("evidenceBundle");
        bundle.put("id", input.evidenceBundleId().toString());
        bundle.put("version", input.evidenceBundleVersion());
        bundle.put("payloadHash", input.evidenceBundleHash());

        ObjectNode matchResult = root.putObject("matchResult");
        matchResult.put("id", input.matchResultId().toString());
        matchResult.put("resultNumber", input.matchResultNumber());
        matchResult.put("resultHash", input.matchResultHash());
        matchResult.put("mappingWatermark", input.mappingWatermark());
        matchResult.set("payload", parse(input.matchResultPayload()));

        ArrayNode mappings = root.putArray("effectiveMappings");
        for (AppliedMapping mapping : input.appliedMappings()) {
            ObjectNode node = mappings.addObject();
            node.put("lineNumber", mapping.lineNumber());
            node.put("itemId", mapping.itemId());
            node.put("purchaseOrderLineId", mapping.purchaseOrderLineId());
        }

        ArrayNode lines = root.putArray("invoiceLines");
        Money total = Money.zero();
        for (EvidenceBundlePayload.EvidenceLine line : input.invoiceLines()) {
            Money lineAmount = Money.of(line.unitPrice()).multiply(Quantity.of(line.quantity()));
            total = total.plus(lineAmount);
            ObjectNode node = lines.addObject();
            node.put("lineNumber", line.lineNumber());
            node.put("rawItemName", line.rawItemName());
            node.put("quantity", line.quantity());
            node.put("unitPrice", line.unitPrice());
            node.put("lineAmount", lineAmount.amount());
        }
        root.put("totalAmount", total.amount());

        ObjectNode purchasing = root.putObject("purchasingSnapshot");
        purchasing.put("snapshotVersion", input.purchasingSnapshotVersion());
        purchasing.put("purchaseOrderVersion", input.purchaseOrderVersion());
        purchasing.put("payloadHash", input.purchasingSnapshotHash());

        String json = write(root);
        return new CanonicalPayload(json, sha256Hex(json));
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String matchPayload) {
        try {
            return mapper.readTree(matchPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored match result payload is not readable", e);
        }
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical review snapshot serialization failed", e);
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
