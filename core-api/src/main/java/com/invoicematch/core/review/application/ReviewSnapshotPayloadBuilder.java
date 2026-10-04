package com.invoicematch.core.review.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
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
 *
 * <p><b>Canonical form.</b> {@code review-snapshot-v2} recursively sorts every
 * JSON object's keys and preserves array order before hashing, so an embedded
 * match payload hashes equally whether it is read from the in-memory
 * computation or reloaded from PostgreSQL {@code jsonb} (which does not preserve
 * key order). {@code review-snapshot-v1} predates this and is retained only to
 * verify snapshots that were already frozen: it serializes the old insertion
 * order. New snapshots are always v2 and existing v1 hashes are never rewritten.
 */
@Component
public class ReviewSnapshotPayloadBuilder {

    /** Canonical form used for every newly frozen snapshot. */
    public static final String SCHEMA_VERSION = "review-snapshot-v2";

    /** Legacy canonical form, retained only to verify already-frozen snapshots. */
    public static final String LEGACY_SCHEMA_VERSION = "review-snapshot-v1";

    private final ObjectMapper mapper = new ObjectMapper();

    public CanonicalPayload canonicalize(ReviewSnapshotPayloadInput input) {
        return canonicalize(input, SCHEMA_VERSION);
    }

    /**
     * Rebuilds the canonical JSON and hash for an explicit schema version. A v2
     * payload recursively sorts object keys; a v1 payload reproduces the legacy
     * insertion-order serialization so an already-frozen v1 snapshot still
     * verifies. Any other version is rejected rather than guessed.
     */
    public CanonicalPayload canonicalize(ReviewSnapshotPayloadInput input, String schemaVersion) {
        if (!SCHEMA_VERSION.equals(schemaVersion) && !LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported review snapshot schemaVersion: " + schemaVersion);
        }
        if(input.proposal()!=null && !SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Legacy snapshot cannot contain an advisory proposal");
        ObjectNode root = buildPayload(input, schemaVersion);
        JsonNode canonical = SCHEMA_VERSION.equals(schemaVersion) ? sortKeys(root) : root;
        String json = write(canonical);
        return new CanonicalPayload(json, sha256Hex(json));
    }

    private ObjectNode buildPayload(ReviewSnapshotPayloadInput input, String schemaVersion) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", schemaVersion);
        root.put("caseId", input.caseId().toString());
        root.put("caseVersion", input.caseVersion());
        if(input.proposal()!=null) root.putObject("proposal").put("id",input.proposal().id().toString())
            .put("payloadHash",input.proposal().payloadHash()).put("contextHash",input.proposal().contextHash());

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

        return root;
    }

    private JsonNode parse(String matchPayload) {
        try {
            return mapper.readTree(matchPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored match result payload is not readable", e);
        }
    }

    /**
     * Recursively rewrites every JSON object with its keys in ascending order
     * while preserving array element order. Applied to v2 payloads so embedded
     * objects (for example the stored match payload) hash equally regardless of
     * whether they came from memory or a PostgreSQL {@code jsonb} round-trip.
     */
    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, sortKeys(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                ordered.add(sortKeys(element));
            }
            return ordered;
        }
        return node.deepCopy();
    }

    private String write(JsonNode node) {
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
