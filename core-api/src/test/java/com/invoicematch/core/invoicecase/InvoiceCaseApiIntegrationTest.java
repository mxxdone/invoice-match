package com.invoicematch.core.invoicecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP contract tests for the P1-03 manual invoice and evidence bundle flow,
 * running the full stack against real PostgreSQL and a real external
 * purchasing stub. Every request is made as the case SUBMITTER, so case
 * ownership and the submitter role are exercised end to end.
 */
@AutoConfigureMockMvc
@WithMockUser(username = "submitter", roles = "SUBMITTER")
class InvoiceCaseApiIntegrationTest extends AbstractPostgresIntegrationTest {

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
    void createValidatesExternalPoAndStartsOpenDraftRevisionV1() throws Exception {
        JsonNode created = createCase("req-create-1");

        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("version").asLong()).isEqualTo(1L);
        assertThat(created.get("supplierId").asText()).isEqualTo(SUPPLIER);
        assertThat(created.get("purchaseOrderId").asText()).isEqualTo(PO_ID);
        assertThat(created.get("currentRevision").get("revisionNumber").asInt()).isEqualTo(1);
        assertThat(created.get("currentRevision").get("status").asText()).isEqualTo("OPEN");
        assertThat(created.get("lines")).isEmpty();
        assertThat(count("purchase_order_snapshot")).isEqualTo(1);
    }

    @Test
    void createRejectsUnknownPurchaseOrderWithoutCreatingCase() throws Exception {
        STUB.respond(404, "{}");

        MvcResult result = performCreate(createRequestBody("req-unknown", SUPPLIER, PO_ID, "INV-1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(result)).isEqualTo("PURCHASE_ORDER_NOT_FOUND");
        assertThat(count("invoice_case")).isZero();
        assertThat(count("purchase_order_snapshot")).isZero();
    }

    @Test
    void createRejectsSupplierMismatchWithoutCreatingCase() throws Exception {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().supplierId("SUP-OTHER").toJson());

        MvcResult result = performCreate(createRequestBody("req-mismatch", SUPPLIER, PO_ID, "INV-1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(result)).isEqualTo("EXTERNAL_REFERENCE_MISMATCH");
        assertThat(count("invoice_case")).isZero();
        assertThat(count("purchase_order_snapshot")).isZero();
    }

    @Test
    void createRejectsUnconfirmedPurchaseOrderWithoutCreatingCase() throws Exception {
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().status("UNCONFIRMED").toJson());

        MvcResult result = performCreate(createRequestBody("req-unconfirmed", SUPPLIER, PO_ID, "INV-1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(result)).isEqualTo("EXTERNAL_FACT_UNCONFIRMED");
        assertThat(count("invoice_case")).isZero();
        assertThat(count("purchase_order_snapshot")).isZero();
    }

    @Test
    void createRejectsExternalTimeoutWithoutCreatingHalfCase() throws Exception {
        STUB.respondAfter(PurchasingPayloads.confirmedPartialReceipt().toJson(), Duration.ofSeconds(3));

        MvcResult result = performCreate(createRequestBody("req-timeout", SUPPLIER, PO_ID, "INV-1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(result)).isEqualTo("PURCHASING_SYSTEM_UNAVAILABLE");
        assertThat(count("invoice_case")).isZero();
        assertThat(count("purchase_order_snapshot")).isZero();
    }

    @Test
    void replaceDraftLinesAtomicallyAndBumpsCaseVersion() throws Exception {
        JsonNode created = createCase("req-1");
        String caseId = created.get("id").asText();
        long version = created.get("version").asLong();

        JsonNode updated = replaceDraft(caseId, "req-2", version, List.of(
                line(1, "A4 Paper", 3, 2500, null), line(2, "Toner", 1, 55000, null)));

        assertThat(updated.get("version").asLong()).isEqualTo(version + 1);
        assertThat(updated.get("lines")).hasSize(2);
        assertThat(updated.get("lines").get(0).get("lineNumber").asInt()).isEqualTo(1);

        JsonNode fetched = getCase(caseId);
        assertThat(fetched.get("lines")).hasSize(2);
        assertThat(fetched.get("lines").get(1).get("rawItemName").asText()).isEqualTo("Toner");

        JsonNode replaced = replaceDraft(caseId, "req-3", fetched.get("version").asLong(),
                List.of(line(1, "A4 Paper 90g", 5, 2600, null)));
        assertThat(replaced.get("lines")).hasSize(1);
        assertThat(fetched.get("version").asLong()).isNotEqualTo(replaced.get("version").asLong());
    }

    @Test
    void replaceDraftRejectsDuplicateLineNumbers() throws Exception {
        JsonNode created = createCase("req-1");
        long version = created.get("version").asLong();
        String caseId = created.get("id").asText();

        MvcResult result = performReplaceDraft(caseId,
                replaceBody("req-2", version, List.of(line(1, "A4", 1, 100, null), line(1, "Toner", 1, 100, null))));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
        assertThat(count("invoice_line")).isZero();
    }

    @Test
    void replaceDraftRejectsMissingLineNumberGap() throws Exception {
        JsonNode created = createCase("req-1");
        long version = created.get("version").asLong();
        String caseId = created.get("id").asText();

        MvcResult result = performReplaceDraft(caseId,
                replaceBody("req-2", version, List.of(line(1, "A4", 1, 100, null), line(3, "Toner", 1, 100, null))));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void replaceDraftRejectsBlankItemAndBadQuantityAndNegativePrice() throws Exception {
        JsonNode created = createCase("req-1");
        long version = created.get("version").asLong();
        String caseId = created.get("id").asText();

        assertThat(performReplaceDraft(caseId, replaceBody("req-blank", version, List.of(line(1, "  ", 1, 100, null))))
                        .getResponse().getStatus())
                .isEqualTo(400);
        assertThat(performReplaceDraft(caseId, replaceBody("req-zero-qty", version, List.of(line(1, "A4", 0, 100, null))))
                        .getResponse().getStatus())
                .isEqualTo(400);
        assertThat(performReplaceDraft(caseId, replaceBody("req-neg-price", version, List.of(line(1, "A4", 1, -1, null))))
                        .getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void replaceDraftValidatesConfirmedItemAgainstCurrentPurchaseOrderSnapshot() throws Exception {
        JsonNode created = createCase("req-1");
        String caseId = created.get("id").asText();
        long version = created.get("version").asLong();

        JsonNode accepted = replaceDraft(caseId, "req-valid-item", version,
                List.of(line(1, "A4 Paper", 3, 2500, "ITEM-A4-80")));
        assertThat(accepted.get("lines").get(0).get("confirmedItemId").asText()).isEqualTo("ITEM-A4-80");

        MvcResult rejected = performReplaceDraft(caseId,
                replaceBody("req-bad-item", accepted.get("version").asLong(),
                        List.of(line(1, "A4 Paper", 3, 2500, "ITEM-UNKNOWN"))));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(rejected)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void confirmedItemIdMustBeAnItemIdNotAPurchaseOrderLineId() throws Exception {
        JsonNode created = createCase("req-1");
        String caseId = created.get("id").asText();

        MvcResult result = performReplaceDraft(caseId,
                replaceBody("req-po-line", created.get("version").asLong(),
                        List.of(line(1, "A4 Paper", 1, 100, "POL-1001-1"))));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
        assertThat(count("invoice_line")).isZero();
    }

    @Test
    void nullDraftLineIsRejected() throws Exception {
        JsonNode created = createCase("req-1");
        String caseId = created.get("id").asText();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", "req-null-line");
        body.put("expectedCaseVersion", created.get("version").asLong());
        body.putArray("lines").addNull();

        MvcResult result = performReplaceDraft(caseId, body);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void requestIdLengthBoundaryIsEnforcedBeforeExternalCall() throws Exception {
        MvcResult tooLong = performCreate(createRequestBody("r".repeat(129), SUPPLIER, PO_ID, "INV-1"));

        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(tooLong)).isEqualTo("VALIDATION_ERROR");
        assertThat(count("invoice_case")).isZero();
        assertThat(count("purchase_order_snapshot")).isZero();

        JsonNode accepted = createCase("r".repeat(128));
        assertThat(accepted.get("id").asText()).isNotBlank();
    }

    @Test
    void punctuationOnlyInvoiceNumberIsRejectedBeforeExternalCall() throws Exception {
        MvcResult result = performCreate(createRequestBody("req-punctuation", SUPPLIER, PO_ID, "---"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
        assertThat(count("invoice_case")).isZero();
        assertThat(count("purchase_order_snapshot")).isZero();
    }

    @Test
    void replaceDraftRejectsStaleExpectedCaseVersion() throws Exception {
        JsonNode created = createCase("req-1");
        String caseId = created.get("id").asText();
        long version = created.get("version").asLong();
        replaceDraft(caseId, "req-2", version, List.of(line(1, "A4", 1, 100, null)));

        MvcResult stale = performReplaceDraft(caseId,
                replaceBody("req-3", version, List.of(line(1, "A4", 2, 100, null))));

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("STALE_CASE_VERSION");
    }

    @Test
    void submitFreezesHashedBundleAndMovesToReviewPending() throws Exception {
        String caseId = prepareDraftWithLines("req-1", List.of(line(1, "A4 Paper", 3, 2500, null)));
        long version = getCase(caseId).get("version").asLong();

        JsonNode submission = submit(caseId, "req-submit", version);

        assertThat(submission.get("status").asText()).isEqualTo("REVIEW_PENDING");
        assertThat(submission.get("evidenceBundle").get("version").asInt()).isEqualTo(1);
        assertThat(submission.get("evidenceBundle").get("payloadHash").asText()).isNotBlank();

        JsonNode bundles = getBundles(caseId);
        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).get("version").asInt()).isEqualTo(1);

        JsonNode detail = getBundle(caseId, 1);
        JsonNode payload = objectMapper.readTree(detail.get("payload").asText());
        assertThat(payload.get("invoiceNumber").asText()).isEqualTo("INV-1");
        assertThat(payload.get("lines")).hasSize(1);
        assertThat(detail.get("payloadHash").asText()).isEqualTo(submission.get("evidenceBundle").get("payloadHash").asText());
    }

    @Test
    void submitRequiresAtLeastOneLine() throws Exception {
        JsonNode created = createCase("req-1");
        String caseId = created.get("id").asText();

        MvcResult result = performSubmit(caseId, submitBody("req-submit", created.get("version").asLong()));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("VALIDATION_ERROR");
        assertThat(count("evidence_bundle")).isZero();
    }

    @Test
    void draftIsNotEditableAfterSubmit() throws Exception {
        String caseId = prepareDraftWithLines("req-1", List.of(line(1, "A4 Paper", 3, 2500, null)));
        long version = submit(caseId, "req-submit", getCase(caseId).get("version").asLong())
                .get("version")
                .asLong();

        MvcResult result = performReplaceDraft(caseId,
                replaceBody("req-edit", version, List.of(line(1, "A4 Paper", 4, 2500, null))));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(result)).isEqualTo("DRAFT_NOT_EDITABLE");
    }

    @Test
    void sameRequestIdSamePayloadReplaysWithoutDuplicating() throws Exception {
        JsonNode first = createCase("req-idem");
        JsonNode replay = createCase("req-idem");

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(count("invoice_case")).isEqualTo(1);

        String caseId = first.get("id").asText();
        long version = first.get("version").asLong();
        JsonNode editFirst = replaceDraft(caseId, "req-edit", version, List.of(line(1, "A4", 1, 100, null)));
        JsonNode editReplay = replaceDraft(caseId, "req-edit", version, List.of(line(1, "A4", 1, 100, null)));

        assertThat(editReplay.get("version").asLong()).isEqualTo(editFirst.get("version").asLong());
        assertThat(count("invoice_line")).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentPayloadIsConflict() throws Exception {
        createCase("req-conflict");

        MvcResult result = performCreate(createRequestBody("req-conflict", SUPPLIER, PO_ID, "INV-DIFFERENT"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(result)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("invoice_case")).isEqualTo(1);
    }

    @Test
    void supplementFlowCopiesFrozenLinesAndKeepsPreviousVersionImmutable() throws Exception {
        String caseId = prepareDraftWithLines("req-1",
                List.of(line(1, "A4 Paper", 3, 2500, null), line(2, "Toner", 1, 55000, null)));
        JsonNode submission = submit(caseId, "req-submit", getCase(caseId).get("version").asLong());
        String v1Hash = submission.get("evidenceBundle").get("payloadHash").asText();
        String v1Payload = getBundle(caseId, 1).get("payload").asText();

        forceSupplementRequired(caseId);
        JsonNode opened = openRevision(caseId, "req-rev", getCase(caseId).get("version").asLong());

        assertThat(opened.get("status").asText()).isEqualTo("SUPPLEMENT_REQUIRED");
        assertThat(opened.get("currentRevision").get("revisionNumber").asInt()).isEqualTo(2);
        assertThat(opened.get("lines")).hasSize(2);

        assertThat(getBundle(caseId, 1).get("payloadHash").asText()).isEqualTo(v1Hash);
        assertThat(getBundle(caseId, 1).get("payload").asText()).isEqualTo(v1Payload);

        replaceDraft(caseId, "req-edit", getCase(caseId).get("version").asLong(),
                List.of(line(1, "A4 Paper", 2, 2500, null)));
        JsonNode v2 = submit(caseId, "req-submit-2", getCase(caseId).get("version").asLong());

        assertThat(v2.get("evidenceBundle").get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("evidenceBundle").get("payloadHash").asText()).isNotEqualTo(v1Hash);
        assertThat(getBundle(caseId, 1).get("payloadHash").asText()).isEqualTo(v1Hash);
        assertThat(getBundles(caseId)).hasSize(2);
    }

    @Test
    void openRevisionRequiresSupplementRequiredState() throws Exception {
        JsonNode created = createCase("req-1");
        String caseId = created.get("id").asText();

        MvcResult result = performOpenRevision(caseId,
                revisionBody("req-rev", created.get("version").asLong()));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(result)).isEqualTo("CASE_STATE_CONFLICT");
    }

    @Test
    void missingResourcesReturnNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/api/invoice-cases/{id}", unknown)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoice-cases/{id}/evidence-bundles/{version}", unknown, 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftLineCountIsBoundedAtOneHundred() throws Exception {
        JsonNode created = createCase("req-lines");
        String caseId = created.get("id").asText();
        long version = created.get("version").asLong();

        List<ObjectNode> tooMany = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            tooMany.add(line(i, "Item", 1, 100, null));
        }
        MvcResult oversize =
                performReplaceDraft(caseId, replaceBody("req-101", version, tooMany));
        assertThat(oversize.getResponse().getStatus()).isEqualTo(400);
        assertThat(count("invoice_line")).isZero();

        List<ObjectNode> atLimit = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            atLimit.add(line(i, "Item", 1, 100, null));
        }
        JsonNode accepted = replaceDraft(caseId, "req-100", version, atLimit);
        assertThat(accepted.get("lines")).hasSize(100);
    }

    @Test
    void hundredLineUnicodeQuoteHeavyDraftIsAuditedAsABoundedSummary() throws Exception {
        JsonNode created = createCase("req-unicode");
        String caseId = created.get("id").asText();
        long version = created.get("version").asLong();
        // Korean (3 UTF-8 bytes) plus JSON-escape-amplifying quotes, near the
        // 500-character DTO limit, repeated across 100 lines.
        String heavyName = "\"\uAC00\uB098\uB2E4".repeat(100);
        assertThat(heavyName.length()).isLessThanOrEqualTo(500);

        List<ObjectNode> lines = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            lines.add(line(i, heavyName, 1, 100, null));
        }

        JsonNode accepted = replaceDraft(caseId, "req-unicode-100", version, lines);
        assertThat(accepted.get("lines")).hasSize(100);

        String afterState = jdbc.queryForObject(
                "select after_state::text from audit_entry"
                        + " where invoice_case_id = ? and action = 'DRAFT_LINES_REPLACED'",
                String.class,
                UUID.fromString(caseId));
        assertThat(afterState.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(65_536);
        assertThat(afterState).doesNotContain("\"truncated\":true");
        assertThat(afterState).contains("rawItemNameSha256");
    }

    private String prepareDraftWithLines(String createRequestId, List<ObjectNode> lines) throws Exception {
        JsonNode created = createCase(createRequestId);
        String caseId = created.get("id").asText();
        replaceDraft(caseId, createRequestId + "-edit", created.get("version").asLong(), lines);
        return caseId;
    }

    private JsonNode createCase(String requestId) throws Exception {
        MvcResult result = performCreate(createRequestBody(requestId, SUPPLIER, PO_ID, "INV-1"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private MvcResult performCreate(ObjectNode body) throws Exception {
        return mockMvc.perform(post("/api/invoice-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private ObjectNode createRequestBody(String requestId, String supplierId, String purchaseOrderId, String invoiceNumber) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("supplierId", supplierId);
        body.put("purchaseOrderId", purchaseOrderId);
        body.put("invoiceNumber", invoiceNumber);
        return body;
    }

    private JsonNode getCase(String caseId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoice-cases/{id}", caseId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private JsonNode replaceDraft(String caseId, String requestId, long expectedVersion, List<ObjectNode> lines)
            throws Exception {
        MvcResult result = performReplaceDraft(caseId, replaceBody(requestId, expectedVersion, lines));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private MvcResult performReplaceDraft(String caseId, ObjectNode body) throws Exception {
        return mockMvc.perform(put("/api/invoice-cases/{id}/draft", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private ObjectNode replaceBody(String requestId, long expectedVersion, List<ObjectNode> lines) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        ArrayNode array = body.putArray("lines");
        lines.forEach(array::add);
        return body;
    }

    private JsonNode submit(String caseId, String requestId, long expectedVersion) throws Exception {
        MvcResult result = performSubmit(caseId, submitBody(requestId, expectedVersion));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private MvcResult performSubmit(String caseId, ObjectNode body) throws Exception {
        return mockMvc.perform(post("/api/invoice-cases/{id}/submit", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private ObjectNode submitBody(String requestId, long expectedVersion) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        return body;
    }

    private JsonNode openRevision(String caseId, String requestId, long expectedVersion) throws Exception {
        MvcResult result = performOpenRevision(caseId, revisionBody(requestId, expectedVersion));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private MvcResult performOpenRevision(String caseId, ObjectNode body) throws Exception {
        return mockMvc.perform(post("/api/invoice-cases/{id}/revisions", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private ObjectNode revisionBody(String requestId, long expectedVersion) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        return body;
    }

    private JsonNode getBundles(String caseId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoice-cases/{id}/evidence-bundles", caseId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private JsonNode getBundle(String caseId, int version) throws Exception {
        MvcResult result = mockMvc
                .perform(get("/api/invoice-cases/{id}/evidence-bundles/{version}", caseId, version))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private ObjectNode line(int lineNumber, String rawItemName, int quantity, long unitPrice, String confirmedItemId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("lineNumber", lineNumber);
        node.put("rawItemName", rawItemName);
        node.put("quantity", quantity);
        node.put("unitPrice", unitPrice);
        if (confirmedItemId == null) {
            node.putNull("confirmedItemId");
        } else {
            node.put("confirmedItemId", confirmedItemId);
        }
        return node;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult result) throws Exception {
        return read(result).get("code").asText();
    }

    private void forceSupplementRequired(String caseId) {
        int updated = jdbc.update(
                "update invoice_case set status = 'SUPPLEMENT_REQUIRED', version = version + 1,"
                        + " updated_at = updated_at + interval '1 second' where id = ?",
                UUID.fromString(caseId));
        assertThat(updated).isEqualTo(1);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
