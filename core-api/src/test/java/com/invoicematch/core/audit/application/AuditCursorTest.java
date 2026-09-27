package com.invoicematch.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cursor round-trip and rejection tests. The cursor is opaque, but decoding it
 * back must reproduce the exact microsecond tuple used for keyset pagination,
 * and garbage must be rejected as a 400-mapped validation error.
 */
class AuditCursorTest {

    @Test
    void roundTripsToMicrosecondPrecision() {
        Instant occurredAt = Instant.parse("2026-09-26T10:15:30.123456Z");
        UUID id = UUID.randomUUID();

        AuditCursor decoded = AuditCursor.decode(AuditCursor.encode(occurredAt, id));

        assertThat(decoded.occurredAt()).isEqualTo(occurredAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void truncatesSubMicrosecondNanos() {
        Instant occurredAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L);

        AuditCursor decoded = AuditCursor.decode(AuditCursor.encode(occurredAt, UUID.randomUUID()));

        assertThat(decoded.occurredAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L, 123_456_000L));
    }

    @Test
    void rejectsUnreadableTokens() {
        assertThatThrownBy(() -> AuditCursor.decode("not-base64!!"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> AuditCursor.decode("YWJj"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> AuditCursor.decode(null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> AuditCursor.decode(""))
                .isInstanceOf(DomainValidationException.class);
    }
}
