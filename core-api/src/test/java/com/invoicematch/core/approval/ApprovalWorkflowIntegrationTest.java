package com.invoicematch.core.approval;

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
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end P1-07 approval tests against real PostgreSQL and the external
 * purchasing stub: happy-path allocation + decision + payment + EXPORT_PENDING +
 * audit, idempotent replay, authorization, the staleness matrix, abnormal match
 * rejection and multi-line FIFO allocation.
 */
@AutoConfigureMockMvc
@WithMockUser(username = "approver", roles = "APPROVER")
class ApprovalWorkflowIntegrationTest extends AbstractPostgresIntegrationTest {

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
    void happyPathAllocatesApprovesCreatesPaymentAndAudit() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        JsonNode approval = approve(caseId, "approve-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 200);

        assertThat(approval.get("status").asText()).isEqualTo("EXPORT_PENDING");
        assertThat(approval.get("amount").asLong()).isEqualTo(150_000L);
        assertThat(approval.get("currency").asText()).isEqualTo("KRW");
        assertThat(approval.get("externalRequestKey").asText())
                .isEqualTo("PAYMENT:" + caseId + ":" + snapshot.get("id").asText());
        assertThat(approval.get("allocations")).hasSize(1);
        JsonNode allocation = approval.get("allocations").get(0);
        assertThat(allocation.get("receiptLineId").asText()).isEqualTo("RCL-1001-1-1");
        assertThat(allocation.get("allocatedQuantity").asInt()).isEqualTo(60);

        assertThat(count("review_decision")).isEqualTo(1);
        assertThat(count("receipt_allocation")).isEqualTo(1);
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(currentStatus(caseId)).isEqualTo("EXPORT_PENDING");

        Map<String, Object> decision = jdbc.queryForMap(
                "select decision, decided_by from review_decision where invoice_case_id = ?", UUID.fromString(caseId));
        assertThat(decision).containsEntry("decision", "APPROVED").containsEntry("decided_by", "approver");

        Map<String, Object> payment = jdbc.queryForMap(
                "select amount, currency, status from payment_request where invoice_case_id = ?",
                UUID.fromString(caseId));
        assertThat(payment).containsEntry("amount", 150_000L).containsEntry("currency", "KRW")
                .containsEntry("status", "PENDING");

        Map<String, Object> audit = jdbc.queryForMap(
                "select action, actor, target_type from audit_entry where invoice_case_id = ? and action = 'APPROVE'",
                UUID.fromString(caseId));
        assertThat(audit).containsEntry("action", "APPROVE").containsEntry("actor", "approver")
                .containsEntry("target_type", "REVIEW_DECISION");
    }

    @Test
    void sameActorReplayReturnsStoredResultWithoutExternalCall() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");
        long version = currentCaseVersion(caseId);

        JsonNode first = approve(caseId, "approve-1", version,
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 200);
        int fetchesAfterFirst = STUB.requestCount();
        int auditsAfterFirst = count("audit_entry");

        JsonNode replay = approve(caseId, "approve-1", version,
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 200);

        assertThat(replay).isEqualTo(first);
        assertThat(STUB.requestCount()).isEqualTo(fetchesAfterFirst);
        assertThat(count("review_decision")).isEqualTo(1);
        assertThat(count("receipt_allocation")).isEqualTo(1);
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("audit_entry")).isEqualTo(auditsAfterFirst);
    }

    @Test
    void multiLineAllocationPreservesFrozenFifoPlanAndMonetaryInvariant() throws Exception {
        STUB.respond(200, multiLinePayload().toJson());
        String caseId = submittedCase(
                List.of(line(1, "A4 Paper", 40, 2500, ITEM_A), line(2, "A4 Paper again", 20, 2500, ITEM_A)));
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        JsonNode approval = approve(caseId, "approve-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 200);

        assertThat(approval.get("amount").asLong()).isEqualTo(150_000L);
        assertThat(approval.get("allocations")).hasSize(3);
        assertThat(count("receipt_allocation")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "select sum(allocated_quantity) from receipt_allocation where invoice_case_id = ?",
                        Long.class,
                        UUID.fromString(caseId)))
                .isEqualTo(60L);
        assertThat(jdbc.queryForObject(
                        "select sum(allocated_quantity) from receipt_allocation"
                                + " where receipt_line_id = 'RCL-1001-1-1'",
                        Long.class))
                .isEqualTo(30L);
        assertThat(jdbc.queryForObject(
                        "select sum(allocated_quantity) from receipt_allocation"
                                + " where receipt_line_id = 'RCL-1001-1-2'",
                        Long.class))
                .isEqualTo(30L);
    }

    @Test
    @WithAnonymousUser
    void unauthenticatedApprovalIs401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/approve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(count("payment_request")).isZero();
    }

    @Test
    void submitterRoleCannotApprove() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", currentCaseVersion(caseId),
                        snapshot.get("id").asText(), snapshot.get("payloadHash").asText())
                .with(user("submitter").roles("SUBMITTER")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("receipt_allocation")).isZero();
        assertThat(count("payment_request")).isZero();
    }

    @Test
    void submitterWhoIsAlsoApproverIsForbiddenFromSelfApproval() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        // The authenticated principal is the case's submittedBy ("submitter") and
        // holds APPROVER, so the role check passes but self-approval must be denied.
        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", currentCaseVersion(caseId),
                        snapshot.get("id").asText(), snapshot.get("payloadHash").asText())
                .with(user("submitter").roles("APPROVER")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("payment_request")).isZero();
    }

    @Test
    void spoofedActorFieldIsIgnored() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        ObjectNode body = approveBody("approve-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText());
        body.put("actor", "ghost");
        body.put("decidedBy", "ghost");
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/approve", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject(
                        "select decided_by from review_decision where invoice_case_id = ?",
                        String.class,
                        UUID.fromString(caseId)))
                .isEqualTo("approver");
    }

    @Test
    void differentActorDoesNotInheritAnotherActorsReplay() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");
        long version = currentCaseVersion(caseId);
        JsonNode first = approve(caseId, "approve-1", version,
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 200);
        assertThat(first.get("status").asText()).isEqualTo("EXPORT_PENDING");

        // Same requestId but a different authenticated approver: actor-scoped
        // idempotency means approver2 cannot read approver's stored response.
        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", version,
                        snapshot.get("id").asText(), snapshot.get("payloadHash").asText())
                .with(user("approver2").roles("APPROVER")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("review_decision")).isEqualTo(1);
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("receipt_allocation")).isEqualTo(1);
    }

    @Test
    void staleCaseVersionIsRejectedWithNoEffects() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", currentCaseVersion(caseId) - 1,
                        snapshot.get("id").asText(), snapshot.get("payloadHash").asText()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(result).get("code").asText()).isEqualTo("STALE_CASE_VERSION");
        assertNoApprovalEffects(caseId);
    }

    @Test
    void supersededSnapshotIsRejectedWithNoEffects() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode first = freeze(caseId, "snap-1");
        freeze(caseId, "snap-2");

        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", currentCaseVersion(caseId),
                        first.get("id").asText(), first.get("payloadHash").asText()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("STALE_REVIEW_TARGET");
        assertThat(error.get("reasons")).anyMatch(node -> node.asText().equals("SUPERSEDED"));
        assertNoApprovalEffects(caseId);
    }

    @Test
    void mismatchedSnapshotHashIsRejectedWithNoEffects() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", currentCaseVersion(caseId),
                        snapshot.get("id").asText(), "not-the-hash"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(result).get("code").asText()).isEqualTo("REVIEW_STATE_CONFLICT");
        assertNoApprovalEffects(caseId);
    }

    @Test
    void changedPurchasingVersionIsRejectedWithNoEffects() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        // The external purchasing snapshot changes after the review snapshot was
        // frozen; approval must refresh, see the mismatch, and reject.
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().snapshotVersion(6).toJson());

        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", currentCaseVersion(caseId),
                        snapshot.get("id").asText(), snapshot.get("payloadHash").asText()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(result).get("code").asText()).isEqualTo("STALE_REVIEW_TARGET");
        assertThat(read(result).get("reasons")).anyMatch(node -> node.asText().equals("PURCHASING_SNAPSHOT"));
        assertNoApprovalEffects(caseId);
    }

    @Test
    void abnormalMatchCannotBeApproved() throws Exception {
        // 200 requested against 60 confirmed produces an exception and normal=false.
        String caseId = submittedCase(1, "A4 Paper", 200, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-1", currentCaseVersion(caseId),
                        snapshot.get("id").asText(), snapshot.get("payloadHash").asText()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(result).get("code").asText()).isEqualTo("APPROVAL_NOT_PERMITTED");
        assertNoApprovalEffects(caseId);
    }

    @Test
    void secondApprovalAfterSuccessIsRejectedAndKeepsOnePayment() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");
        approve(caseId, "approve-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 200);

        // A new requestId (not a replay) cannot approve an already-exported case.
        MvcResult result = mockMvc.perform(approveRaw(caseId, "approve-2", currentCaseVersion(caseId),
                        snapshot.get("id").asText(), snapshot.get("payloadHash").asText()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("review_decision")).isEqualTo(1);
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("receipt_allocation")).isEqualTo(1);
    }

    private void assertNoApprovalEffects(String caseId) throws Exception {
        assertThat(count("receipt_allocation")).isZero();
        assertThat(count("payment_request")).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from review_decision where invoice_case_id = ? and decision = 'APPROVED'",
                        Integer.class,
                        UUID.fromString(caseId)))
                .isZero();
        assertThat(currentStatus(caseId)).isEqualTo("REVIEW_PENDING");
    }

    @Test
    void rawSqlNegativesProtectCrossCaseDuplicateAndOverAllocation() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");
        approve(caseId, "approve-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 200);

        UUID caseUuid = UUID.fromString(caseId);
        UUID decisionId = jdbc.queryForObject(
                "select id from review_decision where invoice_case_id = ? and decision = 'APPROVED'",
                UUID.class,
                caseUuid);
        UUID snapshotId = jdbc.queryForObject(
                "select review_snapshot_id from payment_request where invoice_case_id = ?", UUID.class, caseUuid);
        UUID bundleId = jdbc.queryForObject(
                "select evidence_bundle_id from payment_request where invoice_case_id = ?", UUID.class, caseUuid);
        UUID receiptLineSnapshotId = jdbc.queryForObject(
                "select id from receipt_line_snapshot where receipt_line_id = 'RCL-1001-1-1'", UUID.class);
        UUID unallocatedReceiptLineSnapshotId = jdbc.queryForObject(
                "select id from receipt_line_snapshot where receipt_line_id = 'RCL-1001-1-2'", UUID.class);

        // Cross-case: another case cannot reference this case's decision/snapshot.
        // The target receipt line is unallocated, so the guard passes and the
        // same-case decision/snapshot foreign key is what rejects the insert.
        String otherCaseId = submittedCase("INV-OTHER", List.of(line(1, "A4 Paper", 5, 2500, ITEM_A)));
        UUID otherCaseUuid = UUID.fromString(otherCaseId);
        assertThatThrownBy(() -> insertAllocation(
                        otherCaseUuid,
                        decisionId,
                        snapshotId,
                        bundleId,
                        unallocatedReceiptLineSnapshotId,
                        "RCV-1001-1",
                        "RCL-1001-1-2",
                        1))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Duplicate external payment key / same (case, snapshot).
        assertThatThrownBy(() -> jdbc.update(
                        "insert into payment_request (id, invoice_case_id, purchase_order_id, review_decision_id,"
                                + " review_snapshot_id, evidence_bundle_id, external_request_key, amount, currency,"
                                + " status, created_at) values (?, ?, 'PO-1001', ?, ?, ?, ?, 1, 'KRW', 'PENDING', now())",
                        UUID.randomUUID(),
                        caseUuid,
                        decisionId,
                        snapshotId,
                        bundleId,
                        "PAYMENT:" + caseId + ":" + snapshotId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Over-allocation guard: the line is already fully allocated.
        assertThatThrownBy(() -> insertAllocation(
                        caseUuid, decisionId, snapshotId, bundleId, receiptLineSnapshotId, "RCV-1001-1", "RCL-1001-1-1", 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("exceeds confirmed quantity");

        // Allocations are append-only.
        UUID allocationId = jdbc.queryForObject(
                "select id from receipt_allocation where invoice_case_id = ? limit 1", UUID.class, caseUuid);
        assertThatThrownBy(() -> jdbc.update(
                        "update receipt_allocation set allocated_quantity = 1 where id = ?", allocationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from receipt_allocation where id = ?", allocationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private int insertAllocation(
            UUID caseId,
            UUID decisionId,
            UUID snapshotId,
            UUID bundleId,
            UUID receiptLineSnapshotId,
            String receiptId,
            String receiptLineId,
            int quantity) {
        return jdbc.update(
                "insert into receipt_allocation (id, invoice_case_id, purchase_order_id, review_decision_id,"
                        + " review_snapshot_id, evidence_bundle_id, invoice_line_number, receipt_line_snapshot_id,"
                        + " receipt_id, receipt_line_id, allocated_quantity, created_at)"
                        + " values (?, ?, 'PO-1001', ?, ?, ?, 1, ?, ?, ?, ?, now())",
                UUID.randomUUID(),
                caseId,
                decisionId,
                snapshotId,
                bundleId,
                receiptLineSnapshotId,
                receiptId,
                receiptLineId,
                quantity);
    }

    private String submittedCase(int lineNumber, String itemName, int quantity, long unitPrice, String confirmedItemId)
            throws Exception {
        return submittedCase(List.of(line(lineNumber, itemName, quantity, unitPrice, confirmedItemId)));
    }

    private String submittedCase(List<ObjectNode> lines) throws Exception {
        JsonNode created = createCase("create-1", "INV-1");
        String caseId = created.get("id").asText();
        replaceDraft(caseId, "draft-1", created.get("version").asLong(), lines);
        submit(caseId, "submit-1", currentCaseVersion(caseId));
        return caseId;
    }

    private String submittedCase(String invoiceNumber, List<ObjectNode> lines) throws Exception {
        JsonNode created = createCase("create-" + invoiceNumber, invoiceNumber);
        String caseId = created.get("id").asText();
        replaceDraft(caseId, "draft-" + invoiceNumber, created.get("version").asLong(), lines);
        submit(caseId, "submit-" + invoiceNumber, currentCaseVersion(caseId));
        return caseId;
    }

    private PurchasingPayloads multiLinePayload() {
        return new PurchasingPayloads()
                .snapshotVersion(5)
                .addLine("POL-1001-1", ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                .addReceipt("RCV-1001-1", "CONFIRMED", "2026-01-05", 2,
                        PurchasingPayloads.receiptLine("RCL-1001-1-1", 2, "POL-1001-1", 30))
                .addReceipt("RCV-1001-2", "CONFIRMED", "2026-01-06", 2,
                        PurchasingPayloads.receiptLine("RCL-1001-1-2", 2, "POL-1001-1", 30));
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

    private void runMatch(String caseId, String requestId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/match", caseId)
                        .with(user("operator").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private JsonNode freeze(String caseId, String requestId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", currentCaseVersion(caseId));
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private JsonNode approve(
            String caseId, String requestId, long expectedVersion, String snapshotId, String hash, int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(approveRaw(caseId, requestId, expectedVersion, snapshotId, hash))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return read(result);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder approveRaw(
            String caseId, String requestId, long expectedVersion, String snapshotId, String hash) throws Exception {
        return post("/api/invoice-cases/{id}/approve", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        approveBody(requestId, expectedVersion, snapshotId, hash)));
    }

    private ObjectNode approveBody(String requestId, long expectedVersion, String snapshotId, String hash) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        body.put("reviewSnapshotId", snapshotId);
        body.put("reviewPayloadHash", hash);
        return body;
    }

    private ObjectNode line(int lineNumber, String rawItemName, int quantity, long unitPrice, String confirmedItemId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("lineNumber", lineNumber);
        node.put("rawItemName", rawItemName);
        node.put("quantity", quantity);
        node.put("unitPrice", unitPrice);
        node.put("confirmedItemId", confirmedItemId);
        return node;
    }

    private long currentCaseVersion(String caseId) throws Exception {
        return getCase(caseId).get("version").asLong();
    }

    private String currentStatus(String caseId) throws Exception {
        return getCase(caseId).get("status").asText();
    }

    private JsonNode getCase(String caseId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoice-cases/{id}", caseId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
