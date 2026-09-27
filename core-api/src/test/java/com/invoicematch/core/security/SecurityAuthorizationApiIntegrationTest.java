package com.invoicematch.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
 * End-to-end authorization matrix. Every route is exercised with the local demo
 * identities over real HTTP Basic: unauthenticated protected access is 401, an
 * authenticated but forbidden action is 403, and a SUBMITTER is scoped to the
 * cases they submitted. The actor spoof test proves the client {@code decidedBy}
 * field no longer changes the stored actor.
 */
@AutoConfigureMockMvc
class SecurityAuthorizationApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final String SUBMITTER = "submitter";
    private static final String SUBMITTER2 = "submitter2";
    private static final String APPROVER = "approver";
    private static final String OPERATOR = "operator";
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
    void unauthenticatedProtectedAccessIs401() throws Exception {
        MvcResult read = mockMvc.perform(get("/api/invoice-cases/{id}", UUID.randomUUID())).andReturn();
        MvcResult create = mockMvc.perform(post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(401);
        assertThat(create.getResponse().getStatus()).isEqualTo(401);
        assertThat(count("invoice_case")).isZero();
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoice-cases/{id}", UUID.randomUUID())
                        .with(httpBasic(SUBMITTER, "wrong-password")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void submitterOwnsOwnLifecycleButCannotUseReviewOrOperatorActions() throws Exception {
        String caseId = submittedCase(SUBMITTER);
        int auditsAfterSubmit = count("audit_entry");

        assertThat(getCase(caseId, SUBMITTER).getResponse().getStatus()).isEqualTo(200);

        MvcResult match = mockMvc.perform(post("/api/invoice-cases/{id}/match", caseId)
                        .with(httpBasic(SUBMITTER, password(SUBMITTER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"m-1\"}"))
                .andReturn();
        MvcResult snapshot = mockMvc.perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .with(httpBasic(SUBMITTER, password(SUBMITTER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("s-1", 3)))
                .andReturn();
        MvcResult audit = mockMvc.perform(get("/api/invoice-cases/{id}/audit-entries", caseId)
                        .with(httpBasic(SUBMITTER, password(SUBMITTER))))
                .andReturn();

        assertThat(match.getResponse().getStatus()).isEqualTo(403);
        assertThat(snapshot.getResponse().getStatus()).isEqualTo(403);
        assertThat(audit.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("audit_entry")).isEqualTo(auditsAfterSubmit);
    }

    @Test
    void submitterCannotReadAnotherSubmittersCase() throws Exception {
        String caseId = createCase(SUBMITTER, "INV-1");

        MvcResult forbidden = getCase(caseId, SUBMITTER2);
        MvcResult allowed = getCase(caseId, APPROVER);

        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(allowed).get("submittedBy").asText()).isEqualTo(SUBMITTER);
    }

    @Test
    void approverReviewsButCannotCreateMatchOrEditDraft() throws Exception {
        String caseId = submittedCase(SUBMITTER);
        runMatch(caseId);

        MvcResult freeze = mockMvc.perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .with(httpBasic(APPROVER, password(APPROVER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("snap-approver", currentVersion(caseId, APPROVER))))
                .andReturn();
        MvcResult readSnapshots = mockMvc.perform(get("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .with(httpBasic(APPROVER, password(APPROVER))))
                .andReturn();

        assertThat(freeze.getResponse().getStatus()).isEqualTo(201);
        assertThat(readSnapshots.getResponse().getStatus()).isEqualTo(200);

        assertThat(performAs(APPROVER, post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .getResponse().getStatus())
                .isEqualTo(403);
        assertThat(performAs(APPROVER, post("/api/invoice-cases/{id}/match", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"m-approver\"}")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(performAs(APPROVER, put("/api/invoice-cases/{id}/draft", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"d-approver\",\"expectedCaseVersion\":3,\"lines\":[]}"))
                        .getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void operatorRunsMatchAndReadsButCannotApprove() throws Exception {
        String caseId = submittedCase(SUBMITTER);

        MvcResult match = mockMvc.perform(post("/api/invoice-cases/{id}/match", caseId)
                        .with(httpBasic(OPERATOR, password(OPERATOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"m-operator\"}"))
                .andReturn();
        MvcResult matches = mockMvc.perform(get("/api/invoice-cases/{id}/matches", caseId)
                        .with(httpBasic(OPERATOR, password(OPERATOR))))
                .andReturn();
        MvcResult audit = mockMvc.perform(get("/api/invoice-cases/{id}/audit-entries", caseId)
                        .with(httpBasic(OPERATOR, password(OPERATOR))))
                .andReturn();
        MvcResult freeze = mockMvc.perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .with(httpBasic(OPERATOR, password(OPERATOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("snap-operator", currentVersion(caseId, OPERATOR))))
                .andReturn();

        assertThat(match.getResponse().getStatus()).isEqualTo(201);
        assertThat(matches.getResponse().getStatus()).isEqualTo(200);
        assertThat(audit.getResponse().getStatus()).isEqualTo(200);
        assertThat(freeze.getResponse().getStatus()).isEqualTo(403);
        assertThat(performAs(OPERATOR, post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void actorSpoofCannotChangeStoredDecisionOrAuditActor() throws Exception {
        String caseId = submittedCase(SUBMITTER);
        runMatch(caseId);
        JsonNode snapshot = read(performAs(APPROVER, post("/api/invoice-cases/{id}/review-snapshots", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(freezeBody("snap-spoof", currentVersion(caseId, APPROVER)))));
        long version = currentVersion(caseId, APPROVER);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "map-spoof");
        body.put("expectedCaseVersion", version);
        body.put("reviewSnapshotId", snapshot.get("id").asText());
        body.put("reviewPayloadHash", snapshot.get("payloadHash").asText());
        body.put("lineNumber", 1);
        body.put("itemId", "ITEM-A4-80");
        body.put("decidedBy", SUBMITTER);

        MvcResult mapping = performAs(APPROVER, post("/api/invoice-cases/{id}/mapping-decisions", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(mapping.getResponse().getStatus()).isEqualTo(200);

        JsonNode decisions = read(performAs(APPROVER, get("/api/invoice-cases/{id}/review-decisions", caseId)));
        assertThat(decisions.get(decisions.size() - 1).get("decidedBy").asText()).isEqualTo(APPROVER);

        JsonNode audit = read(performAs(APPROVER, get("/api/invoice-cases/{id}/audit-entries", caseId)));
        assertThat(audit.get("entries").findValuesAsText("action")).contains("ITEM_MAPPED");
        for (JsonNode entry : audit.get("entries")) {
            if (entry.get("action").asText().equals("ITEM_MAPPED")) {
                assertThat(entry.get("actor").asText()).isEqualTo(APPROVER);
                assertThat(entry.get("actorRoles").toString()).contains("APPROVER");
            }
        }
    }

    @Test
    void deniedActionCreatesNoBusinessOrAuditSideEffect() throws Exception {
        String caseId = createCase(SUBMITTER, "INV-1");
        int auditBefore = count("audit_entry");

        MvcResult denied = performAs(APPROVER, put("/api/invoice-cases/{id}/draft", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"denied-draft\",\"expectedCaseVersion\":1,\"lines\":[]}"));

        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("audit_entry")).isEqualTo(auditBefore);
        assertThat(count("invoice_line")).isZero();
    }

    @Test
    void traceIdIsEchoedAndInvalidValueIsReplaced() throws Exception {
        String caseId = createCase(SUBMITTER, "INV-1");

        MvcResult accepted = mockMvc.perform(get("/api/invoice-cases/{id}", caseId)
                        .with(httpBasic(SUBMITTER, password(SUBMITTER)))
                        .header("X-Trace-Id", "client-trace-1"))
                .andReturn();
        MvcResult replaced = mockMvc.perform(get("/api/invoice-cases/{id}", caseId)
                        .with(httpBasic(SUBMITTER, password(SUBMITTER)))
                        .header("X-Trace-Id", "bad value with spaces"))
                .andReturn();
        MvcResult unauthenticated = mockMvc.perform(get("/api/invoice-cases/{id}", caseId)).andReturn();

        assertThat(accepted.getResponse().getHeader("X-Trace-Id")).isEqualTo("client-trace-1");
        assertThat(replaced.getResponse().getHeader("X-Trace-Id")).startsWith("trc-");
        assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
        assertThat(unauthenticated.getResponse().getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void unknownCaseIs404ForAnAuthenticatedSubmitter() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoice-cases/{id}", UUID.randomUUID())
                        .with(httpBasic(SUBMITTER, password(SUBMITTER))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void twoSubmittersCannotReplayEachOthersCreateRequest() throws Exception {
        String body = objectMapper.writeValueAsString(createBodyFor("shared-create", "INV-X"));

        MvcResult first = performAs(SUBMITTER, post("/api/invoice-cases")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstId = read(first).get("id").asText();

        MvcResult second = performAs(SUBMITTER2, post("/api/invoice-cases")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String secondId = read(second).get("id").asText();
        assertThat(secondId).isNotEqualTo(firstId);

        MvcResult replay = performAs(SUBMITTER, post("/api/invoice-cases")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(read(replay).get("id").asText()).isEqualTo(firstId);
        assertThat(count("invoice_case")).isEqualTo(2);
    }

    @Test
    void requestIdConflictIsActorLocalAndReplayStillWorksForTheSamePrincipal() throws Exception {
        String firstPayload = objectMapper.writeValueAsString(createBodyFor("conflict-1", "INV-1"));
        String conflictingPayload = objectMapper.writeValueAsString(createBodyFor("conflict-1", "INV-2"));

        assertThat(performAs(SUBMITTER, post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON).content(firstPayload))
                .getResponse().getStatus())
                .isEqualTo(201);
        assertThat(performAs(SUBMITTER, post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON).content(firstPayload))
                .getResponse().getStatus())
                .isEqualTo(201);
        assertThat(performAs(SUBMITTER, post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictingPayload))
                .getResponse().getStatus())
                .isEqualTo(409);
        // A different principal has its own namespace, so the same request id is free.
        assertThat(performAs(SUBMITTER2, post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictingPayload))
                .getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    void twoApproversDoNotReplayOneAnothersResponse() throws Exception {
        String caseId = submittedCase(SUBMITTER);
        runMatch(caseId);
        String body = freezeBody("s-shared", currentVersion(caseId, APPROVER));

        MvcResult first = performAs(APPROVER, post("/api/invoice-cases/{id}/review-snapshots", caseId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstId = read(first).get("id").asText();

        MvcResult otherApprover = performAsUser("approver2", "APPROVER",
                post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(otherApprover.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(otherApprover).get("id").asText()).isNotEqualTo(firstId);

        MvcResult replay = performAs(APPROVER, post("/api/invoice-cases/{id}/review-snapshots", caseId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(read(replay).get("id").asText()).isEqualTo(firstId);
        assertThat(count("review_snapshot")).isEqualTo(2);
    }

    @Test
    void twoOperatorsDoNotReplayOneAnothersResponse() throws Exception {
        String caseId = submittedCase(SUBMITTER);
        String body = "{\"requestId\":\"m-shared\"}";

        MvcResult first = performAs(OPERATOR, post("/api/invoice-cases/{id}/match", caseId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstId = read(first).get("id").asText();

        MvcResult otherOperator = performAsUser("operator2", "OPERATOR",
                post("/api/invoice-cases/{id}/match", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(otherOperator.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(otherOperator).get("id").asText()).isNotEqualTo(firstId);

        MvcResult replay = performAs(OPERATOR, post("/api/invoice-cases/{id}/match", caseId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(read(replay).get("id").asText()).isEqualTo(firstId);
        assertThat(count("match_result")).isEqualTo(2);
    }

    private String submittedCase(String submitter) throws Exception {
        String caseId = createCase(submitter, "INV-1");
        long version = currentVersion(caseId, submitter);
        ObjectNode draft = objectMapper.createObjectNode();
        draft.put("requestId", "draft-" + caseId);
        draft.put("expectedCaseVersion", version);
        var lines = draft.putArray("lines");
        ObjectNode line = lines.addObject();
        line.put("lineNumber", 1);
        line.put("rawItemName", "A4 Paper");
        line.put("quantity", 60);
        line.put("unitPrice", 2500);
        line.putNull("confirmedItemId");
        assertThat(performAs(submitter, put("/api/invoice-cases/{id}/draft", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draft)))
                .getResponse().getStatus())
                .isEqualTo(200);

        ObjectNode submit = objectMapper.createObjectNode();
        submit.put("requestId", "submit-" + caseId);
        submit.put("expectedCaseVersion", currentVersion(caseId, submitter));
        assertThat(performAs(submitter, post("/api/invoice-cases/{id}/submit", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submit)))
                .getResponse().getStatus())
                .isEqualTo(200);
        return caseId;
    }

    private String createCase(String submitter, String invoiceNumber) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "create-" + UUID.randomUUID());
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", invoiceNumber);
        MvcResult result = performAs(submitter, post("/api/invoice-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result).get("id").asText();
    }

    private void runMatch(String caseId) throws Exception {
        MvcResult result = performAs(OPERATOR, post("/api/invoice-cases/{id}/match", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"m-" + caseId + "\"}"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private long currentVersion(String caseId, String actor) throws Exception {
        return read(getCase(caseId, actor)).get("version").asLong();
    }

    private String freezeBody(String requestId, long expectedVersion) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        return objectMapper.writeValueAsString(body);
    }

    private String createBody() throws Exception {
        return objectMapper.writeValueAsString(createBodyFor("forbidden-" + UUID.randomUUID(), "INV-forbidden"));
    }

    private ObjectNode createBodyFor(String requestId, String invoiceNumber) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", invoiceNumber);
        return body;
    }

    private MvcResult getCase(String caseId, String actor) throws Exception {
        return performAs(actor, get("/api/invoice-cases/{id}", caseId));
    }

    private MvcResult performAs(String actor, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.with(httpBasic(actor, password(actor)))).andReturn();
    }

    private MvcResult performAsUser(String username, String role, MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder.with(user(username).roles(role))).andReturn();
    }

    private static String password(String actor) {
        return actor + "-pass";
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
