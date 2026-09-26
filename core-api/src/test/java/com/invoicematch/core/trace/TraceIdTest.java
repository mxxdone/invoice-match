package com.invoicematch.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trace id boundary and validation tests: a well-formed client id is kept, and
 * anything oversized, empty or containing control characters is replaced with a
 * generated id instead of being trusted.
 */
class TraceIdTest {

    @Test
    void acceptsAWellFormedClientTraceId() {
        assertThat(TraceId.resolve("abc-123.DEF:456_ghi")).isEqualTo("abc-123.DEF:456_ghi");
    }

    @Test
    void acceptsTheMaximumLength() {
        String max = "a".repeat(TraceId.MAX_LENGTH);

        assertThat(TraceId.isValid(max)).isTrue();
        assertThat(TraceId.resolve(max)).isEqualTo(max);
    }

    @Test
    void replacesOversizedTraceId() {
        String oversized = "a".repeat(TraceId.MAX_LENGTH + 1);

        String resolved = TraceId.resolve(oversized);

        assertThat(resolved).isNotEqualTo(oversized);
        assertThat(TraceId.isValid(resolved)).isTrue();
    }

    @Test
    void replacesControlCharactersAndWhitespace() {
        assertThat(TraceId.isValid("abc\u0007def")).isFalse();
        assertThat(TraceId.isValid("abc def")).isFalse();
        assertThat(TraceId.isValid("abc\ndef")).isFalse();
        assertThat(TraceId.resolve("bad\u0000id")).startsWith("trc-");
    }

    @Test
    void generatesWhenMissingOrBlank() {
        assertThat(TraceId.resolve(null)).startsWith("trc-");
        assertThat(TraceId.resolve("")).startsWith("trc-");
        assertThat(TraceId.isValid(TraceId.generate())).isTrue();
    }
}
