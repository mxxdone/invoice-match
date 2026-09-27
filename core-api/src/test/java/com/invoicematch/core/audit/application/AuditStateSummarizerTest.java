package com.invoicematch.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
