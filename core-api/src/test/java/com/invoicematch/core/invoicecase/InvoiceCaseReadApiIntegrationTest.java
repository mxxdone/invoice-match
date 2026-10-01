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
    void invoiceNumberFilterMatchesNormalizedLiteralContains() throws Exception {
        String longNumber = createCase("submitter", "INV-2026-000123");
        String otherNumber = createCase("submitter", "INV-2026-000999");

        // suffix, middle and prefix of a number the caller does not know in full
        assertThat(ids(list("submitter", List.of("invoiceNumber=000123"))))
                .containsExactly(longNumber);
        assertThat(ids(list("submitter", List.of("invoiceNumber=6000123"))))
                .containsExactly(longNumber);
        assertThat(ids(list("submitter", List.of("invoiceNumber=inv-2026-000123"))))
                .containsExactly(longNumber);
        assertThat(ids(list("submitter", List.of("invoiceNumber=INV2026000"))))
                .containsExactlyInAnyOrder(longNumber, otherNumber);
        assertThat(ids(list("submitter", List.of("invoiceNumber=nomatch")))).isEmpty();

        // blank input and input that normalizes to nothing are still "no filter"
        assertThat(ids(list("submitter", List.of("invoiceNumber="))))
                .containsExactlyInAnyOrder(longNumber, otherNumber);
        assertThat(ids(list("submitter", List.of("invoiceNumber=%_"))))
                .containsExactlyInAnyOrder(longNumber, otherNumber);
    }

    @Test
    void purchaseOrderIdFilterMatchesCaseInsensitiveLiteralContains() throws Exception {
        registerPurchaseOrder("PO-ABC-1001");
        registerPurchaseOrder("PO-XYZ-2002");
        registerPurchaseOrder("PO_ABC_1001");
        String dashed = createCase("submitter", "PO-ABC-1001", "INV-PO-1");
        String other = createCase("submitter", "PO-XYZ-2002", "INV-PO-2");
        String underscored = createCase("submitter", "PO_ABC_1001", "INV-PO-3");

        assertThat(ids(list("submitter", List.of("purchaseOrderId=abc"))))
                .containsExactlyInAnyOrder(dashed, underscored);
        assertThat(ids(list("submitter", List.of("purchaseOrderId=1001"))))
                .containsExactlyInAnyOrder(dashed, underscored);
        assertThat(ids(list("submitter", List.of("purchaseOrderId=xyz"))))
                .containsExactly(other);
        assertThat(ids(list("submitter", List.of("purchaseOrderId=PO-"))))
                .containsExactlyInAnyOrder(dashed, other);

        // '_' and '%' are literal, not single/multi character SQL wildcards
        assertThat(ids(list("submitter", List.of("purchaseOrderId=PO_ABC_1001"))))
                .containsExactly(underscored);
        assertThat(ids(list("submitter", List.of("purchaseOrderId=PO%")))).isEmpty();
        // a bare escape character neither turns into a wildcard nor breaks LIKE
        assertThat(ids(list("submitter", List.of("purchaseOrderId=" + "\\")))).isEmpty();
    }

    @Test
    void listCombinesPartialFiltersAndKeepsOwnershipScope() throws Exception {
        registerPurchaseOrder("PO-1001A");
        registerPurchaseOrder("PO-1001B");
        String ownReview = submittedCase("submitter", "PO-1001A", "INV-COMBO-001");
        String ownDraft = createCase("submitter", "PO-1001B", "INV-COMBO-002");
        String otherDraft = createCase("submitter2", "PO-1001A", "INV-COMBO-003");

        assertThat(ids(list("submitter", List.of("purchaseOrderId=1001a", "invoiceNumber=combo"))))
                .containsExactly(ownReview);
        assertThat(ids(list("submitter", List.of("purchaseOrderId=1001b", "invoiceNumber=combo"))))
                .containsExactly(ownDraft);
        assertThat(ids(list("approver", List.of("purchaseOrderId=1001a", "invoiceNumber=combo"))))
                .containsExactlyInAnyOrder(ownReview, otherDraft);
        assertThat(ids(list("submitter", List.of("status=REVIEW_PENDING", "invoiceNumber=combo"))))
                .containsExactly(ownReview);
        // a SUBMITTER's submittedBy filter is ignored, so it cannot widen scope
        // to submitter2 and still returns only the submitter's own cases
        assertThat(ids(list("submitter", List.of("submittedBy=submitter2", "invoiceNumber=combo"))))
                .containsExactlyInAnyOrder(ownReview, ownDraft);
    }

    @Test
    void listKeepsZeroBasedPageContractForPartialInvoiceSearch() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(createCase("submitter", "INV-PAGE-" + String.format("%03d", i)));
        }

        JsonNode full = list("submitter", List.of("invoiceNumber=INV-PAGE", "size=100"));
        assertThat(full.get("totalItems").asLong()).isEqualTo(25);
        assertThat(full.get("totalPages").asInt()).isEqualTo(1);
        assertThat(full.get("page").asInt()).isZero();
        assertThat(full.get("size").asInt()).isEqualTo(100);
        assertThat(full.get("hasNext").asBoolean()).isFalse();
        assertThat(full.get("items")).hasSize(25);
        assertDescendingByCreatedAtThenId(full.get("items"));
        assertThat(ids(full)).containsExactlyInAnyOrderElementsOf(created);

        JsonNode page0 = list("submitter", List.of("invoiceNumber=INV-PAGE", "size=20", "page=0"));
        assertThat(page0.get("items")).hasSize(20);
        assertThat(page0.get("hasNext").asBoolean()).isTrue();

        JsonNode page1 = list("submitter", List.of("invoiceNumber=INV-PAGE", "size=20", "page=1"));
        assertThat(page1.get("page").asInt()).isEqualTo(1);
        assertThat(page1.get("items")).hasSize(5);
        assertThat(page1.get("hasNext").asBoolean()).isFalse();

        JsonNode size50 = list("submitter", List.of("invoiceNumber=INV-PAGE", "size=50"));
        assertThat(size50.get("size").asInt()).isEqualTo(50);
        assertThat(size50.get("items")).hasSize(25);

        JsonNode clamped = list("submitter", List.of("invoiceNumber=INV-PAGE", "size=500"));
        assertThat(clamped.get("size").asInt()).isEqualTo(100);
    }

    @Test
    void oversizedPageRequestsAreRejectedAndNormalEmptyPagesStaySafe() throws Exception {
        // The default size (20) with the largest int page overflows the JDBC
        // offset, so it must be rejected as a validation error rather than
        // silently wrapping or throwing a raw error.
        MvcResult hugeDefault = performAs("submitter", get("/api/invoice-cases").param("page", "2147483647"));
        assertThat(hugeDefault.getResponse().getStatus()).isEqualTo(400);
        JsonNode hugeDefaultError = read(hugeDefault);
        assertThat(hugeDefaultError.get("code").asText()).isEqualTo("VALIDATION_ERROR");

        MvcResult hugeSize100 = performAs("submitter", get("/api/invoice-cases")
                .param("page", "2147483647")
                .param("size", "100"));
        assertThat(hugeSize100.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(hugeSize100).get("code").asText()).isEqualTo("VALIDATION_ERROR");

        createCase("submitter", "INV-EMPTY");

        // A page far past the end is an ordinary empty page, not an error.
        JsonNode outOfRange = list("submitter", List.of("size=20", "page=10"));
        assertThat(outOfRange.get("items")).isEmpty();
        assertThat(outOfRange.get("totalItems").asLong()).isEqualTo(1);
        assertThat(outOfRange.get("hasNext").asBoolean()).isFalse();

        // The largest in-range page with size 1 makes the offset exactly
        // Integer.MAX_VALUE: hasNext must stay false instead of overflowing to
        // a negative value.
        JsonNode maxPage = list("submitter", List.of("size=1", "page=2147483647"));
        assertThat(maxPage.get("page").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(maxPage.get("items")).isEmpty();
        assertThat(maxPage.get("hasNext").asBoolean()).isFalse();

        // A genuinely empty list reports zero pages and no next page.
        jdbc.execute("truncate table invoice_case cascade");
        JsonNode empty = list("submitter", List.of("size=20", "page=0"));
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("totalItems").asLong()).isZero();
        assertThat(empty.get("totalPages").asInt()).isZero();
        assertThat(empty.get("hasNext").asBoolean()).isFalse();
    }

    private static void assertDescendingByCreatedAtThenId(JsonNode items) {
        for (int i = 1; i < items.size(); i++) {
            JsonNode previous = items.get(i - 1);
            JsonNode current = items.get(i);
            int byCreatedAt = previous.get("createdAt").asText().compareTo(current.get("createdAt").asText());
            assertThat(byCreatedAt).isGreaterThanOrEqualTo(0);
            if (byCreatedAt == 0) {
                assertThat(previous.get("id").asText()).isGreaterThanOrEqualTo(current.get("id").asText());
            }
        }
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
        return submittedCase(submitter, PO_ID, invoiceNumber);
    }

    private String submittedCase(String submitter, String purchaseOrderId, String invoiceNumber) throws Exception {
        String caseId = createCase(submitter, purchaseOrderId, invoiceNumber);
        replaceDraft(caseId, currentVersion(caseId, submitter), "draft-" + invoiceNumber);
        submit(caseId, currentVersion(caseId, submitter));
        return caseId;
    }

    private String createCase(String submitter, String invoiceNumber) throws Exception {
        return createCase(submitter, PO_ID, invoiceNumber);
    }

    private String createCase(String submitter, String purchaseOrderId, String invoiceNumber) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "create-" + UUID.randomUUID());
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", purchaseOrderId);
        body.put("invoiceNumber", invoiceNumber);
        MvcResult result = performAs(submitter, post("/api/invoice-cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result).get("id").asText();
    }

    /**
     * Registers a valid external aggregate for a non-default purchase order id
     * so case creation validates it against the stub instead of {@code PO-1001}.
     * Line and receipt ids are namespaced by the purchase order id because those
     * ids are globally unique in the snapshot tables.
     */
    private void registerPurchaseOrder(String purchaseOrderId) {
        String lineA = "POL-" + purchaseOrderId + "-1";
        String lineB = "POL-" + purchaseOrderId + "-2";
        STUB.respondFor(
                purchaseOrderId,
                PurchasingPayloads.confirmedPartialReceipt()
                        .purchaseOrderId(purchaseOrderId)
                        .clearLines()
                        .addLine(lineA, ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                        .addLine(lineB, "ITEM-TONER-BK", "Laser Toner Black", 20, 55000)
                        .clearReceipts()
                        .addReceipt(
                                "RCV-" + purchaseOrderId + "-1",
                                "CONFIRMED",
                                "2026-01-05",
                                2,
                                PurchasingPayloads.receiptLine("RCL-" + purchaseOrderId + "-1-1", 2, lineA, 60),
                                PurchasingPayloads.receiptLine("RCL-" + purchaseOrderId + "-1-2", 2, lineB, 20))
                        .toJson());
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
