package com.invoicematch.core.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Produces a bounded, UTF-8-accurate, canonical JSON serialization of an audit
 * before/after change summary.
 *
 * <p>Canonicalization works on a fresh {@link JsonNode} tree: every object node's
 * fields are recursively sorted by key, nested objects are sorted too, array
 * element order is preserved, and scalar/null semantics are unchanged. It never
 * mutates the caller-owned input. Semantically equal summaries therefore
 * serialize and hash identically regardless of map/field insertion order.
 *
 * <p>The limit is measured on the canonical UTF-8 bytes, not Java chars, so a
 * multibyte or escape-heavy summary cannot slip past a character count and then
 * blow up the row. Normal summaries (line diffs with a bounded name preview and
 * hash) stay well under the limit. If a summary would exceed it, the value is
 * replaced with a deterministic, lossless-to-detect envelope carrying the
 * original canonical byte size and SHA-256 instead of throwing: the audit is
 * never dropped, never partial, and never a 500.
 */
@Component
public class AuditStateSummarizer {

    public static final int MAX_STATE_BYTES = 65_536;

    private final ObjectMapper mapper = new ObjectMapper();

    public String summarize(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode canonical = canonicalize(mapper.valueToTree(value));
        String json;
        try {
            json = mapper.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit change summary serialization failed", e);
        }
        if (utf8Length(json) <= MAX_STATE_BYTES) {
            return json;
        }
        return fallback(json);
    }

    /**
     * Returns a new canonical tree; the input is only read. Object fields are
     * sorted by key, arrays keep their order, and value/null nodes are copied.
     */
    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                ordered.add(canonicalize(element));
            }
            return ordered;
        }
        return node.deepCopy();
    }

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String fallback(String canonicalOriginal) {
        ObjectNode node = mapper.createObjectNode();
        node.put("truncated", true);
        node.put("reason", "summary exceeded the audit size limit");
        node.put("originalBytes", utf8Length(canonicalOriginal));
        node.put("sha256", sha256Hex(canonicalOriginal));
        return node.toString();
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
