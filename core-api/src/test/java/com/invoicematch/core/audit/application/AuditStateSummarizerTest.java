package com.invoicematch.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The audit state summarizer measures UTF-8 bytes, so multibyte and
 * escape-heavy values cannot pass a character count, and an over-limit summary
 * is replaced by a deterministic envelope rather than throwing (which would be a
 * 500 and roll back an otherwise valid business write).
 */
class AuditStateSummarizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX = AuditStateSummarizer.MAX_STATE_BYTES;

    private final AuditStateSummarizer summarizer = new AuditStateSummarizer();

    @Test
    void passesThroughASmallSummary() {
        String json = summarizer.summarize(Map.of("lineNumber", 1, "quantity", 60));

        assertThat(json).contains("\"lineNumber\":1");
        assertThat(truncated(json)).isFalse();
    }

    @Test
    void usesBytesNotCharactersAtTheExactBoundary() {
        // JSON of a string value is value + 2 surrounding quotes.
        String atLimit = "a".repeat(MAX - 2);
        String oneOver = "a".repeat(MAX - 1);

        assertThat(AuditStateSummarizer.utf8Length(MAPPER.valueToTree(atLimit).toString())).isEqualTo(MAX);
        assertThat(truncated(summarizer.summarize(atLimit))).isFalse();

        JsonNode over = read(summarizer.summarize(oneOver));
        assertThat(over.get("truncated").asBoolean()).isTrue();
        assertThat(over.get("originalBytes").asInt()).isEqualTo(MAX + 1);
        assertThat(over.get("sha256").asText()).hasSize(64);
    }

    @Test
    void multibyteValuesAreMeasuredInBytesNotChars() {
        int koreanChars = MAX / 3; // 3 UTF-8 bytes each, far fewer Java chars
        String korean = "\uAC00".repeat(koreanChars);
        assertThat(korean.length()).isLessThan(AuditStateSummarizer.utf8Length(korean));

        JsonNode fallback = read(summarizer.summarize(korean));
        assertThat(fallback.get("truncated").asBoolean()).isTrue();
        assertThat(fallback.get("originalBytes").asInt()).isGreaterThan(MAX);
    }

    @Test
    void escapeAmplificationIsAccountedFor() {
        // Each quote becomes \" in JSON, so 40k quotes are ~80k JSON bytes.
        String quotes = "\"".repeat(40_000);

        JsonNode fallback = read(summarizer.summarize(quotes));

        assertThat(fallback.get("truncated").asBoolean()).isTrue();
        assertThat(fallback.get("originalBytes").asInt()).isGreaterThan(MAX);
    }

    @Test
    void overLimitSummaryKeepsTheAuditDetectableInsteadOfThrowing() {
        JsonNode fallback = read(summarizer.summarize("z".repeat(MAX * 2)));

        assertThat(fallback.get("truncated").asBoolean()).isTrue();
        assertThat(fallback.get("reason").asText()).contains("audit size limit");
        assertThat(fallback.get("sha256").asText()).isNotBlank();
    }

    @Test
    void semanticallyEqualOverLimitMapsWithDifferentInsertionOrderHashTheSame() {
        Map<String, Object> ascending = new LinkedHashMap<>();
        ascending.put("a", "z".repeat(MAX));
        ascending.put("b", "z".repeat(MAX));
        Map<String, Object> descending = new LinkedHashMap<>();
        descending.put("b", "z".repeat(MAX));
        descending.put("a", "z".repeat(MAX));

        String first = summarizer.summarize(ascending);
        String second = summarizer.summarize(descending);

        assertThat(first).isEqualTo(second);
        JsonNode envelope = read(first);
        assertThat(envelope.get("truncated").asBoolean()).isTrue();
        assertThat(envelope.get("sha256").asText()).hasSize(64);
    }

    @Test
    void mapsAreCanonicalizedByKeyButArraysKeepOrder() {
        Map<String, Object> ascending = new LinkedHashMap<>();
        ascending.put("a", 1);
        ascending.put("b", 2);
        Map<String, Object> descending = new LinkedHashMap<>();
        descending.put("b", 2);
        descending.put("a", 1);

        String json = summarizer.summarize(ascending);
        assertThat(json).isEqualTo(summarizer.summarize(descending));
        assertThat(json.indexOf("\"a\"")).isLessThan(json.indexOf("\"b\""));

        String arrayJson = summarizer.summarize(java.util.List.of(3, 1, 2));
        assertThat(arrayJson).isEqualTo("[3,1,2]");
    }

    @Test
    void objectNodesWithOppositeInsertionOrderAreByteIdentical() {
        ObjectNode ascending = MAPPER.createObjectNode();
        ascending.put("a", 1);
        ascending.put("b", 2);
        ObjectNode descending = MAPPER.createObjectNode();
        descending.put("b", 2);
        descending.put("a", 1);

        assertThat(summarizer.summarize(ascending)).isEqualTo(summarizer.summarize(descending));
        assertThat(summarizer.summarize(ascending)).isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    void overLimitObjectNodesWithOppositeInsertionOrderShareTheEnvelopeAndHash() {
        ObjectNode ascending = MAPPER.createObjectNode();
        ascending.put("a", "z".repeat(MAX));
        ascending.put("b", "z".repeat(MAX));
        ObjectNode descending = MAPPER.createObjectNode();
        descending.put("b", "z".repeat(MAX));
        descending.put("a", "z".repeat(MAX));

        String first = summarizer.summarize(ascending);
        String second = summarizer.summarize(descending);

        assertThat(first).isEqualTo(second);
        JsonNode envelope = read(first);
        assertThat(envelope.get("truncated").asBoolean()).isTrue();
        assertThat(envelope.get("sha256").asText()).hasSize(64);
    }

    @Test
    void nestedObjectNodesAreSortedRecursively() {
        ObjectNode outer = MAPPER.createObjectNode();
        ObjectNode inner = outer.putObject("wrapper");
        inner.put("z", 1);
        inner.put("a", 2);
        ObjectNode otherOuter = MAPPER.createObjectNode();
        ObjectNode otherInner = otherOuter.putObject("wrapper");
        otherInner.put("a", 2);
        otherInner.put("z", 1);

        assertThat(summarizer.summarize(outer)).isEqualTo(summarizer.summarize(otherOuter));
        assertThat(summarizer.summarize(outer)).isEqualTo("{\"wrapper\":{\"a\":2,\"z\":1}}");
    }

    @Test
    void arraysPreserveOrderAndDifferentOrderChangesOutputAndHash() {
        ArrayNode forward = MAPPER.createArrayNode();
        forward.add(1);
        forward.add(2);
        forward.add(3);
        ArrayNode reversed = MAPPER.createArrayNode();
        reversed.add(3);
        reversed.add(2);
        reversed.add(1);

        assertThat(summarizer.summarize(forward)).isEqualTo("[1,2,3]");
        assertThat(summarizer.summarize(forward)).isNotEqualTo(summarizer.summarize(reversed));

        ObjectNode forwardHolder = MAPPER.createObjectNode();
        forwardHolder.set("values", forward);
        ObjectNode reversedHolder = MAPPER.createObjectNode();
        reversedHolder.set("values", reversed);
        assertThat(summarizer.summarize(forwardHolder))
                .isNotEqualTo(summarizer.summarize(reversedHolder));
    }

    @Test
    void doesNotMutateCallerOwnedNodes() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("b", 1);
        node.put("a", 2);

        summarizer.summarize(node);

        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("b", "a");
        assertThat(node.toString()).isEqualTo("{\"b\":1,\"a\":2}");
    }

    private static boolean truncated(String json) {
        JsonNode node = read(json);
        return node.has("truncated") && node.get("truncated").asBoolean();
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void byteLengthHelperCountsUtf8() {
        assertThat(AuditStateSummarizer.utf8Length("abc")).isEqualTo(3);
        assertThat(AuditStateSummarizer.utf8Length("\uAC00")).isEqualTo("\uAC00".getBytes(StandardCharsets.UTF_8).length);
    }
}
