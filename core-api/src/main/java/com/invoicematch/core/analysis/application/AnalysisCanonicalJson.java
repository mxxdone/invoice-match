package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic canonical JSON and its SHA-256. Object keys are recursively
 * sorted and arrays keep their order, so semantically identical wire JSON hashes
 * to the same value regardless of the key order the worker happened to emit.
 * No token, receipt time, random value or clock reading is ever included.
 */
final class AnalysisCanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalysisCanonicalJson() {
    }

    /** Canonical compact JSON with recursively sorted object keys. */
    static String canonicalize(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(sort(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical JSON serialization failed", e);
        }
    }

    /** Lowercase hex SHA-256 of the canonical UTF-8 bytes. */
    static String sha256Hex(String canonicalJson) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            ObjectNode result = MAPPER.createObjectNode();
            sorted.forEach((key, value) -> result.set(key, sort(value)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(elements::add);
            elements.forEach(element -> result.add(sort(element)));
            return result;
        }
        return node;
    }
}
