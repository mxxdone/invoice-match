package com.invoicematch.core.matching;

import static com.invoicematch.core.support.PurchasingPayloads.receiptLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end P1-04 tests against real PostgreSQL and the external purchasing
 * stub: the HTTP contract, determinism, append-only immutability and every
 * business exception of the deterministic 3-way match. Matching is an OPERATOR
 * action; case setup is performed as the SUBMITTER.
 */
@AutoConfigureMockMvc
@WithMockUser(username = "operator", roles = "OPERATOR")
class MatchingApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final String ITEM_A = "ITEM-A4-80";
    private static final String ITEM_B = "ITEM-TONER-BK";
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

    @Autowired
    private PurchasingReferenceService purchasingReferenceService;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
    }

    @Test
    void normalMatchPersistsCanonicalResultWithEvidenceAndPlan() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 60, 2500, ITEM_A);

        JsonNode result = runMatch(caseId, "req-match", 201);

        assertThat(result.get("resultHash").asText()).isNotBlank();
        assertThat(result.get("resultNumber").asInt()).isEqualTo(1);
        assertThat(result.get("evidenceBundleId").asText()).isNotBlank();
        JsonNode payload = result.get("payload");
        assertThat(payload.get("schemaVersion").asText()).isEqualTo("match-result-v3");
        assertThat(payload.get("normal").asBoolean()).isTrue();
        assertThat(payload.get("evidenceBundle").get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("evidenceBundle").get("payloadHash").asText()).isNotBlank();
        assertThat(payload.get("purchasingSnapshot").get("snapshotVersion").asLong()).isEqualTo(5);
        assertThat(payload.get("purchasingSnapshot").get("payloadHash").asText()).isNotBlank();
        JsonNode line = payload.get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("MATCHED");
        assertThat(line.get("purchaseOrderLine").get("purchaseOrderLineId").asText())
                .isEqualTo("POL-1001-1");
        assertThat(line.get("expectedAllocationPlan").get(0).get("receiptLineId").asText())
                .isEqualTo("RCL-1001-1-1");
        assertThat(line.get("plannedQuantity").asInt()).isEqualTo(60);

        JsonNode latest = read(mockMvc.perform(get("/api/invoice-cases/{id}/match", caseId))
                .andReturn());
        assertThat(latest.get("resultHash").asText())
                .isEqualTo(result.get("resultHash").asText());
        assertThat(getMatches(caseId)).hasSize(1);
    }

    @Test
    void quantityExcessAndUnitPriceMismatchAreReportedTogether() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 100, 2501, ITEM_A);

        JsonNode result = runMatch(caseId, "req-match", 201);

        List<String> types = exceptionTypes(result);
        assertThat(types)
                .containsExactly("QUANTITY_EXCEEDS_RECEIPT_BALANCE", "UNIT_PRICE_MISMATCH");
        assertThat(result.get("payload").get("normal").asBoolean()).isFalse();
        JsonNode line = result.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("plannedQuantity").asInt()).isEqualTo(60);
    }

    @Test
    void unconfirmedItemIsReportedWithoutInventingAMapping() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 10, 2500, null);

        JsonNode result = runMatch(caseId, "req-match", 201);

        JsonNode line = result.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("ITEM_UNCONFIRMED");
        assertThat(line.get("confirmedItemId").isNull()).isTrue();
        assertThat(exceptionTypes(result)).containsExactly("ITEM_UNCONFIRMED");
    }

    @Test
    void severalCandidatePurchaseOrderLinesCauseEvidenceInsufficient() throws Exception {
        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .addLine("POL-1001-3", ITEM_A, "Premium Copy Paper A4 80g", 10, 2500)
                        .toJson());
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 10, 2500, ITEM_A);

        JsonNode result = runMatch(caseId, "req-match", 201);

        JsonNode line = result.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(line.get("candidatePoLineIds")).hasSize(2);
        assertThat(exceptionTypes(result)).containsExactly("EVIDENCE_INSUFFICIENT");
    }

    @Test
    void removingThePurchaseOrderLineAfterSubmissionCausesEvidenceInsufficient() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 10, 2500, ITEM_A);

        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .snapshotVersion(6)
                        .purchaseOrderVersion(4)
                        .clearLines()
                        .addLine("POL-1001-2", ITEM_B, "Laser Toner Black", 20, 55000)
                        .clearReceipts()
                        .addReceipt(
                                "RCV-1001-1",
                                "CONFIRMED",
                                "2026-01-05",
                                2,
                                receiptLine("RCL-1001-1-2", 2, "POL-1001-2", 20))
                        .toJson());
        refreshSnapshot();

        JsonNode result = runMatch(caseId, "req-match", 201);

        JsonNode line = result.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(line.get("candidatePoLineIds")).isEmpty();
    }

    @Test
    void inactiveReceiptLinesAreExcludedFromAvailableQuantity() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 10, 2500, ITEM_A);

        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .snapshotVersion(6)
                        .purchaseOrderVersion(4)
                        .clearReceipts()
                        .addReceipt(
                                "RCV-1001-1",
                                "CONFIRMED",
                                "2026-01-05",
                                3,
                                receiptLine("RCL-1001-1-2", 2, "POL-1001-2", 20))
                        .toJson());
        refreshSnapshot();

        JsonNode result = runMatch(caseId, "req-match", 201);

        JsonNode line = result.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("availableConfirmedQuantity").asInt()).isZero();
        assertThat(line.get("expectedAllocationPlan")).isEmpty();
        assertThat(exceptionTypes(result)).containsExactly("QUANTITY_EXCEEDS_RECEIPT_BALANCE");
    }

    @Test
    void duplicateBusinessInvoiceIsSuspectedButStillStored() throws Exception {
        String firstCase = submittedCaseWithLine("req-1", "INV-DUP", 1, "A4 Paper", 10, 2500, ITEM_A);
        String secondCase = submittedCaseWithLine("req-2", "INV-DUP", 1, "A4 Paper", 10, 2500, ITEM_A);

        JsonNode result = runMatch(secondCase, "req-match", 201);

        JsonNode exception = result.get("payload").get("exceptions").get(0);
        assertThat(exception.get("type").asText()).isEqualTo("DUPLICATE_INVOICE_SUSPECTED");
        assertThat(exception.get("details").get("normalizedInvoiceNumber").asText())
                .isEqualTo("INVDUP");
        assertThat(exception.get("details").get("otherCaseIds").get(0).asText())
                .isEqualTo(firstCase);
        assertThat(count("invoice_case")).isEqualTo(2);
    }

    @Test
    void reRunWithNewRequestIdIsAppendOnlyButSamePayloadAndHash() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 60, 2500, ITEM_A);

        JsonNode first = runMatch(caseId, "req-match-1", 201);
        JsonNode second = runMatch(caseId, "req-match-2", 201);

        assertThat(first.get("resultNumber").asInt()).isEqualTo(1);
        assertThat(second.get("resultNumber").asInt()).isEqualTo(2);
        assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(second.get("resultHash").asText()).isEqualTo(first.get("resultHash").asText());
        assertThat(second.get("payload")).isEqualTo(first.get("payload"));
        assertThat(getMatches(caseId)).hasSize(2);
    }

    @Test
    void sameRequestIdReplaysWithoutAppending() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 60, 2500, ITEM_A);

        JsonNode first = runMatch(caseId, "req-match", 201);
        JsonNode replay = runMatch(caseId, "req-match", 201);

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(count("match_result")).isEqualTo(1);
    }

    @Test
    void persistedMatchResultIsImmutableAtTheDatabase() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 60, 2500, ITEM_A);
        String matchId = runMatch(caseId, "req-match", 201).get("id").asText();

        assertThatThrownBy(() -> jdbc.update(
                        "update match_result set result_hash = 'tampered' where id = ?", UUID.fromString(matchId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "delete from match_result where id = ?", UUID.fromString(matchId)))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("match_result")).isEqualTo(1);
    }

    @Test
    void matchingRequiresFrozenReviewableState() throws Exception {
        JsonNode created = createCase("req-1", "INV-1");
        String caseId = created.get("id").asText();

        MvcResult notReviewable = mockMvc.perform(post("/api/invoice-cases/{id}/match", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody("req-match")))
                .andReturn();
        assertThat(notReviewable.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void supplementRequiredStateIsRejectedBecauseEvidenceIsStale() throws Exception {
        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 10, 2500, ITEM_A);
        forceSupplementRequired(caseId);

        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/match", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody("req-match")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(result).get("code").asText()).isEqualTo("MATCH_STATE_CONFLICT");
        assertThat(count("match_result")).isZero();
    }

    @Test
    void unknownCaseIsNotFoundAndMissingResultIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(post("/api/invoice-cases/{id}/match", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody("req-match")))
                .andExpect(status().isNotFound());

        String caseId = submittedCaseWithLine("req-1", 1, "A4 Paper", 10, 2500, ITEM_A);
        MvcResult latest = mockMvc.perform(get("/api/invoice-cases/{id}/match", caseId))
                .andReturn();
        assertThat(latest.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(latest).get("code").asText()).isEqualTo("MATCH_RESULT_NOT_FOUND");
        assertThat(getMatches(caseId)).isEmpty();
    }

    private void refreshSnapshot() {
        purchasingReferenceService.refresh(
                new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of(SUPPLIER)));
    }

    private void forceSupplementRequired(String caseId) {
        int updated = jdbc.update(
                "update invoice_case set status = 'SUPPLEMENT_REQUIRED', version = version + 1,"
                        + " updated_at = updated_at + interval '1 second' where id = ?",
                UUID.fromString(caseId));
        assertThat(updated).isEqualTo(1);
    }

    private String submittedCaseWithLine(
            String requestId, int lineNumber, String itemName, int quantity, long unitPrice, String confirmedItemId)
            throws Exception {
        return submittedCaseWithLine(requestId, "INV-1", lineNumber, itemName, quantity, unitPrice, confirmedItemId);
    }

    private String submittedCaseWithLine(
            String requestId,
            String invoiceNumber,
            int lineNumber,
            String itemName,
            int quantity,
            long unitPrice,
            String confirmedItemId)
            throws Exception {
        JsonNode created = createCase(requestId, invoiceNumber);
        String caseId = created.get("id").asText();
        replaceDraft(caseId, requestId + "-edit", created.get("version").asLong(),
                List.of(line(lineNumber, itemName, quantity, unitPrice, confirmedItemId)));
        submit(caseId, requestId + "-submit", getCase(caseId).get("version").asLong());
        return caseId;
    }

    private JsonNode runMatch(String caseId, String requestId, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/match", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody(requestId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return read(result);
    }

    private List<String> exceptionTypes(JsonNode result) {
        ArrayNode exceptions = (ArrayNode) result.get("payload").get("exceptions");
        return exceptions.findValues("type").stream().map(JsonNode::asText).toList();
    }

    private JsonNode createCase(String requestId, String invoiceNumber) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", invoiceNumber);
        MvcResult result = mockMvc.perform(post("/api/invoice-cases")
                        .with(user("submitter").roles("SUBMITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private JsonNode getCase(String caseId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoice-cases/{id}", caseId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private void replaceDraft(String caseId, String requestId, long expectedVersion, List<ObjectNode> lines)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        ArrayNode array = body.putArray("lines");
        lines.forEach(array::add);
        MvcResult result = mockMvc.perform(put("/api/invoice-cases/{id}/draft", caseId)
                        .with(user("submitter").roles("SUBMITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private void submit(String caseId, String requestId, long expectedVersion) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/submit", caseId)
                        .with(user("submitter").roles("SUBMITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private JsonNode getMatches(String caseId) throws Exception {
        return read(mockMvc.perform(get("/api/invoice-cases/{id}/matches", caseId)).andReturn());
    }

    private String matchBody(String requestId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        return objectMapper.writeValueAsString(body);
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

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
