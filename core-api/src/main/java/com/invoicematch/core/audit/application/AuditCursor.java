package com.invoicematch.core.audit.application;

import com.invoicematch.core.shared.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset cursor for the audit history. It carries the {@code (occurred_at, id)}
 * of the last row of the previous page so the next page is read with a strict
 * tuple comparison in the database. The token is opaque base64url text; an
 * unreadable or out-of-range token is a 400, never a trusted value.
 */
public record AuditCursor(Instant occurredAt, UUID id) {

    public static AuditCursor decode(String token) {
        if (token == null || token.isBlank()) {
            throw new DomainValidationException("cursor must not be blank");
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("cursor is not a valid audit cursor");
        }
        int separator = decoded.indexOf(':');
        if (separator <= 0 || separator == decoded.length() - 1) {
            throw new DomainValidationException("cursor is not a valid audit cursor");
        }
        try {
            long epochMicros = Long.parseLong(decoded.substring(0, separator));
            UUID id = UUID.fromString(decoded.substring(separator + 1));
            // PostgreSQL timestamptz keeps microsecond precision, so truncate to
            // micros for a stable, comparable tuple.
            Instant occurredAt = Instant.ofEpochSecond(
                    Math.floorDiv(epochMicros, 1_000_000L), Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
            return new AuditCursor(occurredAt, id);
        } catch (RuntimeException e) {
            throw new DomainValidationException("cursor is not a valid audit cursor");
        }
    }

    public static String encode(Instant occurredAt, UUID id) {
        long epochMicros = occurredAt.getEpochSecond() * 1_000_000L + occurredAt.getNano() / 1_000L;
        String raw = epochMicros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
