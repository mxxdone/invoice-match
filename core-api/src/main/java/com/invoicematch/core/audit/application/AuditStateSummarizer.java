package com.invoicematch.core.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Produces a bounded, UTF-8-accurate JSON serialization of an audit before/after
 * change summary.
 *
 * <p>The limit is measured in bytes, not Java chars, so a multibyte or
 * escape-heavy summary cannot slip past a character count and then blow up the
 * row. Normal summaries (line diffs with a bounded name preview and hash) stay
 * well under the limit. If a summary would exceed it, the value is replaced with
 * a deterministic, lossless-to-detect envelope carrying the original byte size
 * and SHA-256 instead of throwing: the audit is never dropped, never partial,
 * and never a 500.
 */
@Component
public class AuditStateSummarizer {

    public static final int MAX_STATE_BYTES = 65_536;

    private final ObjectMapper mapper = new ObjectMapper();

    public String summarize(Object value) {
        if (value == null) {
            return null;
        }
        String json;
        try {
            json = mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit change summary serialization failed", e);
        }
        if (utf8Length(json) <= MAX_STATE_BYTES) {
            return json;
        }
        return fallback(json);
    }

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String fallback(String original) {
        ObjectNode node = mapper.createObjectNode();
        node.put("truncated", true);
        node.put("reason", "summary exceeded the audit size limit");
        node.put("originalBytes", utf8Length(original));
        node.put("sha256", sha256Hex(original));
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
