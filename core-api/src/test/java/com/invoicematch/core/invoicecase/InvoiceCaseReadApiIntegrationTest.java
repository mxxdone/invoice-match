package com.invoicematch.core.invoicecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * P1-10 read-contract integration tests against real PostgreSQL: login identity,
 * the role-scoped filtered work list with server-side paging, the hand-off read
 * and the enriched 409 stale payloads. It also verifies 401/403 boundaries.
 */
@AutoConfigureMockMvc
class InvoiceCaseReadApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final String ITEM_A = "ITEM-A4-80";
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
    void unauthenticatedReadsAre401() throws Exception {
        assertThat(mockMvc.perform(get("/api/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/invoice-cases")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/invoice-cases/{id}/handoff", UUID.randomUUID()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void meReturnsAuthenticatedIdentityAndRoles() throws Exception {
        JsonNode submitter = read(performAs("submitter", get("/api/me")));
        JsonNode approver = read(performAs("approver", get("/api/me")));

        assertThat(submitter.get("username").asText()).isEqualTo("submitter");
        assertThat(names(submitter.get("roles"))).containsExactly("SUBMITTER");
        assertThat(approver.get("username").asText()).isEqualTo("approver");
        assertThat(names(approver.get("roles"))).containsExactly("APPROVER");
    }

    @Test
    void listIsScopedToOwnershipForSubmittersButNotForReviewers() throws Exception {
        String own = createCase("submitter", "INV-OWN");
        String other = createCase("submitter2", "INV-OTHER");

        JsonNode submitter = list("submitter", List.of());
        JsonNode approver = list("approver", List.of());
        JsonNode operator = list("operator", List.of());
        JsonNode widened = list("submitter", List.of("submittedBy=submitter2"));

        assertThat(ids(submitter)).containsExactly(own);
        assertThat(ids(approver)).containsExactlyInAnyOrder(own, other);
        assertThat(ids(operator)).containsExactlyInAnyOrder(own, other);
        // A SUBMITTER's submittedBy filter can never widen the row scope.
        assertThat(ids(widened)).containsExactly(own).doesNotContain(other);
    }

    @Test
    void listFiltersAndPagesOnTheServer() throws Exception {
        String draftA = createCase("submitter", "INV-A");
        String review = submittedCase("submitter", "INV-B");
        String draftC = createCase("submitter", "INV-C");

        assertThat(ids(list("submitter", List.of("status=DRAFT")))).containsExactlyInAnyOrder(draftA, draftC);
        assertThat(ids(list("submitter", List.of("status=REVIEW_PENDING")))).containsExactly(review);
        assertThat(ids(list("submitter", List.of("invoiceNumber=inv b")))).containsExactly(review);
        assertThat(ids(list("submitter", List.of("supplierId=SUP-1"))))
                .containsExactlyInAnyOrder(draftA, review, draftC);
        assertThat(ids(list("submitter", List.of("supplierId=SUP-NONE")))).isEmpty();

        JsonNode firstPage = list("submitter", List.of("size=1", "page=0"));
        assertThat(firstPage.get("totalItems").asLong()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(3);
        assertThat(firstPage.get("size").asInt()).isEqualTo(1);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        assertThat(firstPage.get("items")).hasSize(1);

        Set<String> paged = new LinkedHashSet<>();
        for (int page = 0; page < 3; page++) {
            JsonNode slice = list("submitter", List.of("size=1", "page=" + page));
            JsonNode items = slice.get("items");
            assertThat(items).hasSize(1);
            paged.add(items.get(0).get("id").asText());
            assertThat(slice.get("hasNext").asBoolean()).isEqualTo(page < 2);
        }
        assertThat(paged).containsExactlyInAnyOrder(draftA, review, draftC);

        MvcResult invalid = performAs("submitter", get("/api/invoice-cases").param("status", "NOT_A_STATUS"));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void handoffIsNullUntilApprovedThenExposesPaymentAndOutboxState() throws Exception {
        String caseId = submittedCase("submitter", "INV-1");
        runMatch(caseId);
        JsonNode snapshot = freeze(caseId);

        JsonNode before = read(performAs("submitter", get("/api/invoice-cases/{id}/handoff", caseId)));
        assertThat(before.get("caseStatus").asText()).isEqualTo("REVIEW_PENDING");
        assertThat(before.get("payment").isNull()).isTrue();

        approve(caseId, snapshot);

        JsonNode after = read(performAs("approver", get("/api/invoice-cases/{id}/handoff", caseId)));
        assertThat(after.get("caseStatus").asText()).isEqualTo("EXPORT_PENDING");
        JsonNode payment = after.get("payment");
        assertThat(payment.get("paymentStatus").asText()).isEqualTo("NOT_SENT");
        assertThat(payment.get("outboxStatus").asText()).isEqualTo("READY");
        assertThat(payment.get("exportVersion").asLong()).isEqualTo(1L);
        assertThat(payment.get("amount").asLong()).isEqualTo(150_000L);
        assertThat(payment.get("currency").asText()).isEqualTo("KRW");
        assertThat(payment.get("externalRequestKey").asText())
                .isEqualTo("PAYMENT:" + caseId + ":" + snapshot.get("id").asText());

        assertThat(read(performAs("operator", get("/api/invoice-cases/{id}/handoff", caseId)))
                .get("payment").get("paymentStatus").asText())
                .isEqualTo("NOT_SENT");

        MvcResult forbidden = performAs("submitter2", get("/api/invoice-cases/{id}/handoff", caseId));
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(performAs("approver", get("/api/invoice-cases/{id}/handoff", UUID.randomUUID()))
                .getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void staleCaseVersionConflictCarriesLatestVersion() throws Exception {
        String caseId = createCase("submitter", "INV-1");
        replaceDraft(caseId, 1L, "draft-1");
        long latest = currentVersion(caseId, "submitter");

        MvcResult stale = performAs("submitter", put("/api/invoice-cases/{id}/draft", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody("draft-stale", 1L)));

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = read(stale);
        assertThat(error.get("code").asText()).isEqualTo("STALE_CASE_VERSION");
        assertThat(error.get("caseId").asText()).isEqualTo(caseId);
        assertThat(error.get("expectedVersion").asLong()).isEqualTo(1L);
        assertThat(error.get("latestVersion").asLong()).isEqualTo(latest);
    }

    @Test
    void staleReviewTargetConflictCarriesReasonsAndCurrentCase() throws Exception {
        String caseId = submittedCase("submitter", "INV-1");
        runMatch(caseId);
        JsonNode snapshot = freeze(caseId);
        runMatch(caseId);
        long currentVersion = currentVersion(caseId, "approver");

        MvcResult stale = performAs("approver", post("/api/invoice-cases/{id}/reject", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody(currentVersion, snapshot.get("id").asText(), snapshot.get("payloadHash").asText())));

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = read(stale);
        assertThat(error.get("code").asText()).isEqualTo("STALE_REVIEW_TARGET");
        assertThat(names(error.get("reasons"))).contains("MATCH_RESULT");
        assertThat(error.get("currentCaseVersion").asLong()).isEqualTo(currentVersion);
        assertThat(error.get("currentCaseStatus").asText()).isEqualTo("REVIEW_PENDING");
    }

    private String submittedCase(String submitter, String invoiceNumber) throws Exception {
        String caseId = createCase(submitter, invoiceNumber);
        replaceDraft(caseId, currentVersion(caseId, submitter), "draft-" + invoiceNumber);
        submit(caseId, currentVersion(caseId, submitter));
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

    private void replaceDraft(String caseId, long expectedVersion, String requestId) throws Exception {
        MvcResult result = performAs("submitter", put("/api/invoice-cases/{id}/draft", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody(requestId, expectedVersion)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private String draftBody(String requestId, long expectedVersion) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        ArrayNode lines = body.putArray("lines");
        ObjectNode line = lines.addObject();
        line.put("lineNumber", 1);
        line.put("rawItemName", "A4 Paper");
        line.put("quantity", 60);
        line.put("unitPrice", 2500);
        line.put("confirmedItemId", ITEM_A);
        return objectMapper.writeValueAsString(body);
    }

    private void submit(String caseId, long expectedVersion) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "submit-" + caseId);
        body.put("expectedCaseVersion", expectedVersion);
        MvcResult result = performAs("submitter", post("/api/invoice-cases/{id}/submit", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void runMatch(String caseId) throws Exception {
        MvcResult result = performAs("operator", post("/api/invoice-cases/{id}/match", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"match-" + UUID.randomUUID() + "\"}"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private JsonNode freeze(String caseId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "snap-" + UUID.randomUUID());
        body.put("expectedCaseVersion", currentVersion(caseId, "approver"));
        MvcResult result = performAs("approver", post("/api/invoice-cases/{id}/review-snapshots", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private void approve(String caseId, JsonNode snapshot) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "approve-" + UUID.randomUUID());
        body.put("expectedCaseVersion", currentVersion(caseId, "approver"));
        body.put("reviewSnapshotId", snapshot.get("id").asText());
        body.put("reviewPayloadHash", snapshot.get("payloadHash").asText());
        MvcResult result = performAs("approver", post("/api/invoice-cases/{id}/approve", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private String rejectBody(long expectedVersion, String snapshotId, String hash) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "reject-" + UUID.randomUUID());
        body.put("expectedCaseVersion", expectedVersion);
        body.put("reviewSnapshotId", snapshotId);
        body.put("reviewPayloadHash", hash);
        body.put("reason", "stale subject test");
        return objectMapper.writeValueAsString(body);
    }

    private long currentVersion(String caseId, String actor) throws Exception {
        return read(performAs(actor, get("/api/invoice-cases/{id}", caseId))).get("version").asLong();
    }

    private JsonNode list(String actor, List<String> params) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/invoice-cases");
        for (String param : params) {
            String[] parts = param.split("=", 2);
            builder.param(parts[0], parts[1]);
        }
        MvcResult result = performAs(actor, builder);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private MvcResult performAs(String actor, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.with(user(actor).roles(roleFor(actor)))).andReturn();
    }

    private static String roleFor(String actor) {
        return switch (actor) {
            case "approver" -> "APPROVER";
            case "operator" -> "OPERATOR";
            default -> "SUBMITTER";
        };
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private static List<String> names(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
