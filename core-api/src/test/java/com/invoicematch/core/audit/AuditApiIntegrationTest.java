package com.invoicematch.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Audit history tests: completeness for every in-scope write, atomicity with the
 * business transaction, idempotent replay semantics, cursor ordering and case
 * scoping, trace capture and redaction.
 */
@AutoConfigureMockMvc
class AuditApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final StubPurchasingServer STUB;

    static {
        try {
            STUB = new StubPurchasingServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void purchasingProperties(DynamicPropertyRegistry registry) {
        registry.add("purchasing-system.base-url", STUB::baseUrl);
        registry.add("purchasing-system.connect-timeout", () -> "1s");
        registry.add("purchasing-system.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
    }

    @Test
    void recordsEveryInScopeBusinessWriteWithActorAndStructuredChange() throws Exception {
        String caseId = fullAuditedFlow();

        Set<String> actions = auditActions(caseId);
        assertThat(actions).contains(
                "CASE_CREATED",
                "DRAFT_LINES_REPLACED",
                "CASE_SUBMITTED",
                "MATCH_RUN",
                "REVIEW_SNAPSHOT_FROZEN",
                "ITEM_MAPPED",
                "SUPPLEMENT_REQUESTED",
                "SUPPLEMENT_REVISION_OPENED",
                "CASE_REJECTED");

        JsonNode page = audit(caseId, null, 100);
        for (JsonNode entry : page.get("entries")) {
            assertThat(entry.get("actor").asText()).isNotBlank();
            assertThat(entry.get("traceId").asText()).isNotBlank();
            assertThat(entry.get("occurredAt").asText()).isNotBlank();
            assertThat(entry.get("before").isNull() || entry.get("before").isObject()).isTrue();
            assertThat(entry.get("after").isNull() || entry.get("after").isObject()).isTrue();
        }
    }

    @Test
    void replayedIdempotentRequestDoesNotDuplicateAudit() throws Exception {
        String caseId = createCase("INV-1");
        long version = currentVersion(caseId);
        String body = draftBody("draft-replay", version, 1);
        assertThat(performAs("submitter", put("/api/invoice-cases/{id}/draft", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .getResponse().getStatus())
                .isEqualTo(200);
        int afterFirst = count("audit_entry");

        assertThat(performAs("submitter", put("/api/invoice-cases/{id}/draft", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .getResponse().getStatus())
                .isEqualTo(200);

        assertThat(count("audit_entry")).isEqualTo(afterFirst);
    }

    @Test
    void failedBusinessWriteLeavesNoAuditEntry() throws Exception {
        String caseId = createCase("INV-1");
        int before = count("audit_entry");
        long version = currentVersion(caseId);

        MvcResult result = performAs("submitter", post("/api/invoice-cases/{id}/submit", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"submit-empty\",\"expectedCaseVersion\":" + version + "}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(count("audit_entry")).isEqualTo(before);
    }

    @Test
    void auditFailureRollsBackTheBusinessMutation() throws Exception {
        jdbc.execute("create or replace function fail_audit() returns trigger as $$"
                + " begin raise exception 'audit forced failure'; end; $$ language plpgsql");
        jdbc.execute("create trigger trg_fail_audit before insert on audit_entry"
                + " for each row execute function fail_audit()");
        try {
            int casesBefore = count("invoice_case");
            int snapshotsBefore = count("purchase_order_snapshot");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> performAs("submitter", post("/api/invoice-cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody())))
                    .isInstanceOf(jakarta.servlet.ServletException.class);

            assertThat(count("invoice_case")).isEqualTo(casesBefore);
            assertThat(count("purchase_order_snapshot")).isEqualTo(snapshotsBefore);
        } finally {
            jdbc.execute("drop trigger if exists trg_fail_audit on audit_entry");
            jdbc.execute("drop function if exists fail_audit()");
        }
    }

    @Test
    void cursorPaginationIsNewestFirstWithoutGapsOrDuplicates() throws Exception {
        String caseId = createCase("INV-1");
        for (int i = 1; i <= 4; i++) {
            assertThat(performAs("submitter", put("/api/invoice-cases/{id}/draft", caseId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(draftBody("draft-" + i, currentVersion(caseId), 1)))
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        List<String> collected = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = audit(caseId, cursor, 2);
            for (JsonNode entry : page.get("entries")) {
                collected.add(entry.get("id").asText());
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);

        assertThat(collected).hasSize(5);
        assertThat(new LinkedHashSet<>(collected)).hasSize(5);
        assertThat(collected.get(0)).isEqualTo(latestAuditId(caseId));
    }

    @Test
    void historyIsScopedToTheRequestedCase() throws Exception {
        String first = createCase("INV-1");
        String second = createCase("INV-2");

        JsonNode page = audit(first, null, 100);
        for (JsonNode entry : page.get("entries")) {
            assertThat(entry.get("invoiceCaseId").asText()).isEqualTo(first);
        }
        assertThat(page.get("entries").findValuesAsText("invoiceCaseId")).doesNotContain(second);
    }

    @Test
    void invalidCursorIsRejectedAsValidationError() throws Exception {
        String caseId = createCase("INV-1");

        MvcResult result = performAs("approver", get("/api/invoice-cases/{id}/audit-entries", caseId)
                .param("cursor", "!!!not-a-cursor"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(result).get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void auditRedactsCredentialsAndAuthorizationInputs() throws Exception {
        String caseId = createCase("INV-1");

        Integer leaked = jdbc.queryForObject(
                "select count(*) from audit_entry where"
                        + " coalesce(before_state::text, '') || coalesce(after_state::text, '')"
                        + " || coalesce(request_id, '') ~* '(basic |authorization|password|approver-pass)'",
                Integer.class);

        assertThat(leaked).isZero();
        assertThat(caseId).isNotBlank();
    }

    @Test
    void suppliedTraceIdIsStoredForTheAuditedWrite() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "trace-create");
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", "INV-TRACE");
        MvcResult created = mockMvc.perform(post("/api/invoice-cases")
                        .with(httpBasic("submitter", "submitter-pass"))
                        .header("X-Trace-Id", "trace-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        String caseId = read(created).get("id").asText();

        JsonNode page = audit(caseId, null, 100);
        JsonNode createEntry = page.get("entries").get(page.get("entries").size() - 1);
        assertThat(createEntry.get("action").asText()).isEqualTo("CASE_CREATED");
        assertThat(createEntry.get("traceId").asText()).isEqualTo("trace-create-1");
    }

    @Test
    void remappingAuditContainsTheExactSemanticDiff() throws Exception {
        String caseId = createAndSubmit("INV-1");
        runMatch(caseId, "m-1");
        JsonNode first = freeze(caseId, "snap-1");
        recordMapping(caseId, first, "ITEM-A4-80", "map-a");
        JsonNode successor = read(performAs("approver",
                get("/api/invoice-cases/{id}/review-snapshots/latest", caseId)));
        recordMapping(caseId, successor, "ITEM-TONER-BK", "map-b");

        List<JsonNode> mapped = new ArrayList<>();
        for (JsonNode entry : audit(caseId, null, 100).get("entries")) {
            if (entry.get("action").asText().equals("ITEM_MAPPED")) {
                mapped.add(entry);
            }
        }
        assertThat(mapped).hasSize(2);

        JsonNode replacement = mapped.get(0);
        assertThat(replacement.get("before").get("itemId").asText()).isEqualTo("ITEM-A4-80");
        assertThat(replacement.get("before").get("lineNumber").asInt()).isEqualTo(1);
        assertThat(replacement.get("after").get("itemId").asText()).isEqualTo("ITEM-TONER-BK");
        assertThat(replacement.get("after").get("lineNumber").asInt()).isEqualTo(1);

        JsonNode initial = mapped.get(1);
        assertThat(initial.get("before").isNull()).isTrue();
        assertThat(initial.get("after").get("itemId").asText()).isEqualTo("ITEM-A4-80");
    }

    @Test
    void oversizedRequestBodyIsRejectedWithoutBusinessOrAuditEffect() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "oversize");
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", "X".repeat(300_000));
        int casesBefore = count("invoice_case");
        int auditsBefore = count("audit_entry");

        MvcResult result = performAs("submitter", post("/api/invoice-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));

        assertThat(result.getResponse().getStatus()).isEqualTo(413);
        assertThat(read(result).get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(count("invoice_case")).isEqualTo(casesBefore);
        assertThat(count("audit_entry")).isEqualTo(auditsBefore);
    }

    private String fullAuditedFlow() throws Exception {
        String caseId = createAndSubmit("INV-1");
        runMatch(caseId, "m-1");
        JsonNode snapshot = freeze(caseId, "snap-1");
        recordMapping(caseId, snapshot, "ITEM-A4-80", "map-1");
        JsonNode successor = read(performAs("approver",
                get("/api/invoice-cases/{id}/review-snapshots/latest", caseId)));
        supplement(caseId, successor, "need correction", "supp-1");

        assertThat(performAs("submitter", post("/api/invoice-cases/{id}/revisions", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"open-2\",\"expectedCaseVersion\":" + currentVersion(caseId) + "}"))
                .getResponse().getStatus())
                .isEqualTo(201);
        assertThat(performAs("submitter", put("/api/invoice-cases/{id}/draft", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody("draft-2", currentVersion(caseId), 1)))
                .getResponse().getStatus())
                .isEqualTo(200);
        assertThat(performAs("submitter", post("/api/invoice-cases/{id}/submit", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"submit-2\",\"expectedCaseVersion\":" + currentVersion(caseId) + "}"))
                .getResponse().getStatus())
                .isEqualTo(200);
        runMatch(caseId, "m-2");
        JsonNode second = freeze(caseId, "snap-2");

        assertThat(performAs("approver", post("/api/invoice-cases/{id}/reject", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody(caseId, "reject-1", second, "rejected reason")))
                .getResponse().getStatus())
                .isEqualTo(200);
        return caseId;
    }

    private String createAndSubmit(String invoiceNumber) throws Exception {
        String caseId = createCase(invoiceNumber);
        assertThat(performAs("submitter", put("/api/invoice-cases/{id}/draft", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody("draft-1", currentVersion(caseId), 1)))
                .getResponse().getStatus())
                .isEqualTo(200);
        assertThat(performAs("submitter", post("/api/invoice-cases/{id}/submit", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"submit-1\",\"expectedCaseVersion\":" + currentVersion(caseId) + "}"))
                .getResponse().getStatus())
                .isEqualTo(200);
        return caseId;
    }

    private String createCase(String invoiceNumber) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "create-" + UUID.randomUUID());
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", invoiceNumber);
        MvcResult result = performAs("submitter", post("/api/invoice-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result).get("id").asText();
    }

    private String createBody() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "forced-" + UUID.randomUUID());
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", "INV-forced");
        return objectMapper.writeValueAsString(body);
    }

    private void runMatch(String caseId, String requestId) throws Exception {
        assertThat(performAs("operator", post("/api/invoice-cases/{id}/match", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .getResponse().getStatus())
                .isEqualTo(201);
    }

    private JsonNode freeze(String caseId, String requestId) throws Exception {
        MvcResult result = performAs("approver", post("/api/invoice-cases/{id}/review-snapshots", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"expectedCaseVersion\":" + currentVersion(caseId) + "}"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private void recordMapping(String caseId, JsonNode snapshot, String itemId, String requestId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", currentVersion(caseId));
        body.put("reviewSnapshotId", snapshot.get("id").asText());
        body.put("reviewPayloadHash", snapshot.get("payloadHash").asText());
        body.put("lineNumber", 1);
        body.put("itemId", itemId);
        MvcResult result = performAs("approver", post("/api/invoice-cases/{id}/mapping-decisions", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void supplement(String caseId, JsonNode snapshot, String reason, String requestId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", currentVersion(caseId));
        body.put("reviewSnapshotId", snapshot.get("id").asText());
        body.put("reviewPayloadHash", snapshot.get("payloadHash").asText());
        body.put("reason", reason);
        MvcResult result = performAs("approver", post("/api/invoice-cases/{id}/supplement-requests", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private String decisionBody(String caseId, String requestId, JsonNode snapshot, String reason) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", currentVersion(caseId));
        body.put("reviewSnapshotId", snapshot.get("id").asText());
        body.put("reviewPayloadHash", snapshot.get("payloadHash").asText());
        body.put("reason", reason);
        return objectMapper.writeValueAsString(body);
    }

    private String draftBody(String requestId, long expectedVersion, int lineNumber) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        ObjectNode line = body.putArray("lines").addObject();
        line.put("lineNumber", lineNumber);
        line.put("rawItemName", "A4 Paper");
        line.put("quantity", 60);
        line.put("unitPrice", 2500);
        line.putNull("confirmedItemId");
        return objectMapper.writeValueAsString(body);
    }

    private long currentVersion(String caseId) throws Exception {
        return read(performAs("approver", get("/api/invoice-cases/{id}", caseId)))
                .get("version").asLong();
    }

    private JsonNode audit(String caseId, String cursor, int limit) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/invoice-cases/{id}/audit-entries", caseId)
                .param("limit", String.valueOf(limit));
        if (cursor != null) {
            builder = builder.param("cursor", cursor);
        }
        MvcResult result = performAs("approver", builder);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private Set<String> auditActions(String caseId) throws Exception {
        Set<String> actions = new LinkedHashSet<>();
        for (JsonNode entry : audit(caseId, null, 100).get("entries")) {
            actions.add(entry.get("action").asText());
        }
        return actions;
    }

    private String latestAuditId(String caseId) {
        return jdbc.queryForObject(
                "select id::text from audit_entry where invoice_case_id = ? order by occurred_at desc, id desc limit 1",
                String.class,
                UUID.fromString(caseId));
    }

    private MvcResult performAs(String actor, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.with(httpBasic(actor, actor + "-pass"))).andReturn();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
