package com.invoicematch.core.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read side of the audit history. One keyset query per page, ordered by
 * {@code (occurred_at DESC, id DESC)} and scoped to a single case, so it never
 * leaks another case's events and never triggers an N+1 series of queries.
 */
@Service
public class AuditQueryService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final String COLUMNS = "id, invoice_case_id, occurred_at, actor, actor_roles, action,"
            + " target_type, target_id, business_version, before_state, after_state, request_id, trace_id";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuditHistoryPage history(UUID caseId, String cursorToken, int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        List<AuditEntryView> rows = new ArrayList<>();
        if (cursorToken == null || cursorToken.isBlank()) {
            jdbc.query(
                    "select " + COLUMNS + " from audit_entry where invoice_case_id = ?"
                            + " order by occurred_at desc, id desc limit ?",
                    rs -> {
                        rows.add(map(rs));
                    },
                    caseId,
                    limit + 1);
        } else {
            AuditCursor cursor = AuditCursor.decode(cursorToken);
            jdbc.query(
                    "select " + COLUMNS + " from audit_entry where invoice_case_id = ?"
                            + " and (occurred_at, id) < (?, ?) order by occurred_at desc, id desc limit ?",
                    rs -> {
                        rows.add(map(rs));
                    },
                    caseId,
                    java.sql.Timestamp.from(cursor.occurredAt()),
                    cursor.id(),
                    limit + 1);
        }

        boolean hasMore = rows.size() > limit;
        List<AuditEntryView> page = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor = hasMore
                ? AuditCursor.encode(page.get(page.size() - 1).occurredAt(), page.get(page.size() - 1).id())
                : null;
        return new AuditHistoryPage(page, nextCursor);
    }

    private AuditEntryView map(ResultSet rs) throws SQLException {
        List<String> roles = new ArrayList<>();
        String storedRoles = rs.getString("actor_roles");
        if (storedRoles != null && !storedRoles.isBlank()) {
            for (String role : storedRoles.split(",")) {
                if (!role.isBlank()) {
                    roles.add(role.strip());
                }
            }
        }
        return new AuditEntryView(
                rs.getObject("id", UUID.class),
                rs.getObject("invoice_case_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor"),
                List.copyOf(roles),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getLong("business_version"),
                parse(rs.getString("before_state")),
                parse(rs.getString("after_state")),
                rs.getString("request_id"),
                rs.getString("trace_id"));
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit change summary is not readable", e);
        }
    }
}
