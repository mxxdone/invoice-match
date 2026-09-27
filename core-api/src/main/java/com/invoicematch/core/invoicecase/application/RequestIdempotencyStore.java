package com.invoicematch.core.invoicecase.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores the response of a completed write request so that a retry with the
 * same {@code (scope, resource, actor, requestId)} replays the original
 * response instead of repeating the side effect. The key is namespaced by the
 * server-derived authenticated principal, so one principal can never replay or
 * read another principal's stored response. The unique constraint plus
 * {@code ON CONFLICT DO NOTHING} serialize concurrent identical requests: the
 * loser waits for the winner to commit and then reads back its record.
 *
 * <p>The reservation is inserted in the same transaction as the business
 * effect, so a rolled-back request leaves no record and a committed request
 * always carries its response.
 */
@Component
public class RequestIdempotencyStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper mapper;

    public RequestIdempotencyStore(JdbcTemplate jdbc, Clock clock, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.mapper = mapper;
    }

    public record StoredResponse(String requestHash, int status, String body) {
    }

    public sealed interface BeginResult permits BeginResult.Started, BeginResult.Replay {

        record Started() implements BeginResult {
        }

        record Replay(StoredResponse response) implements BeginResult {
        }
    }

    public Optional<StoredResponse> find(String scope, String resourceKey, String actor, String requestId) {
        List<StoredResponse> rows = jdbc.query(
                "select request_hash, response_status, response_body from idempotency_record"
                        + " where scope = ? and resource_key = ? and actor = ? and request_id = ?",
                (rs, rowNum) -> {
                    Integer status = rs.getObject("response_status", Integer.class);
                    String body = rs.getString("response_body");
                    if (status == null || body == null) {
                        throw new IllegalStateException("Idempotency record for " + scope + "/" + resourceKey + "/"
                                + actor + "/" + requestId + " is committed without a stored response");
                    }
                    return new StoredResponse(rs.getString("request_hash"), status, body);
                },
                scope,
                resourceKey,
                actor,
                requestId);
        return rows.stream().findFirst();
    }

    /**
     * Reserves the request id for this principal and transaction, or returns the
     * stored response when it was already processed. Blocks on a concurrent
     * identical request until that transaction commits or rolls back.
     */
    public BeginResult begin(String scope, String resourceKey, String actor, String requestId, String requestHash) {
        int inserted = jdbc.update(
                "insert into idempotency_record"
                        + " (id, scope, resource_key, actor, request_id, request_hash, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?)"
                        + " on conflict (scope, resource_key, actor, request_id) do nothing",
                UUID.randomUUID(),
                scope,
                resourceKey,
                actor,
                requestId,
                requestHash,
                Timestamp.from(clock.instant()));
        if (inserted == 1) {
            return new BeginResult.Started();
        }
        StoredResponse existing = find(scope, resourceKey, actor, requestId)
                .orElseThrow(() -> new IllegalStateException("Idempotency record for " + scope + "/" + resourceKey
                        + "/" + actor + "/" + requestId + " vanished"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(scope, resourceKey, requestId);
        }
        return new BeginResult.Replay(existing);
    }

    public void recordResponse(
            String scope, String resourceKey, String actor, String requestId, int status, Object body) {
        int updated = jdbc.update(
                "update idempotency_record set response_status = ?, response_body = ?"
                        + " where scope = ? and resource_key = ? and actor = ? and request_id = ?",
                status,
                write(body),
                scope,
                resourceKey,
                actor,
                requestId);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record for " + scope + "/" + resourceKey + "/" + actor
                    + "/" + requestId + " was not reserved by this transaction");
        }
    }

    public <T> T decode(StoredResponse stored, Class<T> type) {
        try {
            return mapper.readValue(stored.body(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is not readable", e);
        }
    }

    private String write(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Idempotent response serialization failed", e);
        }
    }
}
