package com.invoicematch.core.review;

import static com.invoicematch.core.support.PurchasingPayloads.receiptLine;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end P1-05 review workflow tests against real PostgreSQL and the
 * external purchasing stub: snapshot freezing, mapping/re-match, supplement,
 * rejection, freshness reasons, idempotency and immutability of the frozen
 * sources.
 */
@AutoConfigureMockMvc
class ReviewWorkflowApiIntegrationTest extends AbstractPostgresIntegrationTest {

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
    void freezeInitialSnapshotFromLatestMatchResultWithCanonicalContent() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        JsonNode match = runMatch(caseId, "match-1");

        JsonNode snapshot = freeze(caseId, "snap-1");

        assertThat(snapshot.get("snapshotNumber").asInt()).isEqualTo(1);
        assertThat(snapshot.get("matchResultNumber").asInt()).isEqualTo(1);
        assertThat(snapshot.get("matchResultId").asText()).isEqualTo(match.get("id").asText());
        assertThat(snapshot.get("payloadHash").asText()).isNotBlank();
        JsonNode payload = snapshot.get("payload");
        assertThat(payload.get("schemaVersion").asText()).isEqualTo("review-snapshot-v1");
        assertThat(payload.get("evidenceBundle").get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("matchResult").get("resultHash").asText())
                .isEqualTo(match.get("resultHash").asText());
        assertThat(payload.get("effectiveMappings")).isEmpty();
        assertThat(payload.get("invoiceLines").get(0).get("lineAmount").asLong()).isEqualTo(150000L);
        assertThat(payload.get("totalAmount").asLong()).isEqualTo(150000L);
        assertThat(payload.get("purchasingSnapshot").get("snapshotVersion").asLong()).isEqualTo(5L);

        JsonNode latest = read(mockMvc.perform(get("/api/invoice-cases/{id}/review-snapshots/latest", caseId))
                .andReturn());
        assertThat(latest.get("id").asText()).isEqualTo(snapshot.get("id").asText());
        assertThat(getHistory(caseId)).hasSize(1);

        JsonNode freshness = read(mockMvc.perform(
                        get("/api/invoice-cases/{id}/review-snapshots/1/freshness", caseId))
                .andReturn());
        assertThat(freshness.get("current").asBoolean()).isTrue();
        assertThat(freshness.get("reasons")).isEmpty();
    }

    @Test
    void snapshotCannotBeFrozenWithoutAMatchResult() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);

        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("snap-1", currentCaseVersion(caseId))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(result).get("code").asText()).isEqualTo("REVIEW_STATE_CONFLICT");
        assertThat(count("review_snapshot")).isZero();
    }

    @Test
    void mappingUnconfirmedLineRematchesAndSupersedesOldSnapshot() throws Exception {
        String caseId = submittedCase(1, "Premium Copy Paper A4", 60, 2500, null);
        runMatch(caseId, "match-1");
        JsonNode first = freeze(caseId, "snap-1");
        long versionBeforeMapping = currentCaseVersion(caseId);

        JsonNode mapping = recordMapping(caseId, "map-1", currentCaseVersion(caseId),
                first.get("id").asText(), first.get("payloadHash").asText(), 1, ITEM_A, 200);

        assertThat(mapping.get("decision").get("decision").asText()).isEqualTo("MAPPING");
        assertThat(mapping.get("decision").get("mappingItemId").asText()).isEqualTo(ITEM_A);
        assertThat(mapping.get("decision").get("mappingPoLineId").asText()).isEqualTo("POL-1001-1");
        JsonNode successor = mapping.get("successorSnapshot");
        assertThat(successor.get("snapshotNumber").asInt()).isEqualTo(2);
        assertThat(successor.get("matchResultNumber").asInt()).isEqualTo(2);
        JsonNode successorPayload = successor.get("payload");
        assertThat(successorPayload.get("effectiveMappings")).hasSize(1);
        assertThat(successorPayload.get("effectiveMappings").get(0).get("itemId").asText()).isEqualTo(ITEM_A);
        assertThat(successorPayload.get("effectiveMappings").get(0).get("purchaseOrderLineId").asText())
                .isEqualTo("POL-1001-1");
        assertThat(successorPayload.get("matchResult").get("payload").get("lineOutcomes").get(0).get("status").asText())
                .isEqualTo("MATCHED");

        assertThat(currentCaseVersion(caseId)).isGreaterThan(versionBeforeMapping);
        assertThat(count("evidence_bundle")).isEqualTo(1);
        assertThat(getEvidenceBundle(caseId, 1).get("payloadHash").asText())
                .isEqualTo(first.get("payload").get("evidenceBundle").get("payloadHash").asText());

        JsonNode oldFreshness = read(mockMvc.perform(
                        get("/api/invoice-cases/{id}/review-snapshots/1/freshness", caseId))
                .andReturn());
        assertThat(oldFreshness.get("current").asBoolean()).isFalse();
        assertThat(reasonNames(oldFreshness)).contains("SUPERSEDED", "CASE_VERSION", "MAPPING");

        assertThat(getDecisions(caseId)).hasSize(1);
    }

    @Test
    void invalidMappingLineItemAndAmbiguousPurchaseOrderLineLeaveNoRows() throws Exception {
        String caseId = submittedCase(1, "Premium Copy Paper A4", 60, 2500, null);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        MvcResult badLine = recordMappingRaw(caseId, "map-line", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 99, ITEM_A);
        assertThat(badLine.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(badLine).get("code").asText()).isEqualTo("REVIEW_TARGET_INVALID");

        MvcResult badItem = recordMappingRaw(caseId, "map-item", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 1, "ITEM-NOPE");
        assertThat(badItem.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(badItem).get("code").asText()).isEqualTo("REVIEW_TARGET_INVALID");

        assertThat(count("review_decision")).isZero();
        assertThat(count("review_snapshot")).isEqualTo(1);
    }

    @Test
    void ambiguousPurchaseOrderLineForTheChosenItemIsRejected() throws Exception {
        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .addLine("POL-1001-3", ITEM_A, "Premium Copy Paper A4 80g", 10, 2500)
                        .toJson());
        String caseId = submittedCase(1, "Premium Copy Paper A4", 60, 2500, null);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        MvcResult result = recordMappingRaw(caseId, "map-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), 1, ITEM_A);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(result).get("code").asText()).isEqualTo("REVIEW_TARGET_INVALID");
        assertThat(count("review_decision")).isZero();
    }

    @Test
    void mappingIsScopedToBundleAndANewBundleDoesNotInheritIt() throws Exception {
        String caseId = submittedCase(1, "Premium Copy Paper A4", 60, 2500, null);
        runMatch(caseId, "match-1");
        JsonNode first = freeze(caseId, "snap-1");
        JsonNode mapping = recordMapping(caseId, "map-1", currentCaseVersion(caseId),
                first.get("id").asText(), first.get("payloadHash").asText(), 1, ITEM_A, 200);
        String successorId = mapping.get("successorSnapshot").get("id").asText();

        // Supplement to correct the evidence, then open and submit vNext.
        supplement(caseId, "supp-1", currentCaseVersion(caseId), successorId,
                mapping.get("successorSnapshot").get("payloadHash").asText(), "please correct");
        openRevision(caseId, "rev-1", currentCaseVersion(caseId));
        submit(caseId, "submit-2", currentCaseVersion(caseId));
        assertThat(count("evidence_bundle")).isEqualTo(2);

        JsonNode newMatch = runMatch(caseId, "match-2");
        assertThat(newMatch.get("evidenceBundleId").asText()).isNotEqualTo(first.get("evidenceBundleId").asText());
        assertThat(newMatch.get("payload").get("appliedMappings")).isEmpty();
        assertThat(newMatch.get("payload").get("lineOutcomes").get(0).get("status").asText())
                .isEqualTo("ITEM_UNCONFIRMED");
    }

    @Test
    void effectiveMappingUsesItsExactLineWhenItemBecomesAmbiguousAfterRefresh() throws Exception {
        String caseId = submittedCase(1, "Premium Copy Paper A4", 60, 2500, null);
        runMatch(caseId, "match-1");
        JsonNode first = freeze(caseId, "snap-1");
        recordMapping(caseId, "map-1", currentCaseVersion(caseId),
                first.get("id").asText(), first.get("payloadHash").asText(), 1, ITEM_A, 200);

        // The item now resolves to two active lines; item-based resolution would
        // be ambiguous, but the mapping pinned POL-1001-1 and must keep it.
        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .snapshotVersion(6)
                        .purchaseOrderVersion(4)
                        .addLine("POL-1001-9", ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                        .toJson());
        refreshSnapshot();

        JsonNode rematch = runMatch(caseId, "match-2");
        JsonNode line = rematch.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("MATCHED");
        assertThat(line.get("purchaseOrderLine").get("purchaseOrderLineId").asText()).isEqualTo("POL-1001-1");
        assertThat(rematch.get("payload").get("appliedMappings").get(0).get("purchaseOrderLineId").asText())
                .isEqualTo("POL-1001-1");
    }

    @Test
    void effectiveMappingDoesNotRetargetWhenItsChosenLineDisappears() throws Exception {
        String caseId = submittedCase(1, "Premium Copy Paper A4", 60, 2500, null);
        runMatch(caseId, "match-1");
        JsonNode first = freeze(caseId, "snap-1");
        recordMapping(caseId, "map-1", currentCaseVersion(caseId),
                first.get("id").asText(), first.get("payloadHash").asText(), 1, ITEM_A, 200);

        // POL-1001-1 is gone; only POL-1001-9 (same item) remains. The mapping
        // must not silently retarget to the new line.
        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .snapshotVersion(6)
                        .purchaseOrderVersion(4)
                        .clearLines()
                        .addLine("POL-1001-9", ITEM_A, "Premium Copy Paper A4 80g", 100, 2500)
                        .clearReceipts()
                        .addReceipt(
                                "RCV-1001-1",
                                "CONFIRMED",
                                "2026-01-05",
                                3,
                                receiptLine("RCL-1001-1-9", 3, "POL-1001-9", 60))
                        .toJson());
        refreshSnapshot();

        JsonNode rematch = runMatch(caseId, "match-2");
        JsonNode line = rematch.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(line.get("purchaseOrderLine").isNull()).isTrue();
        assertThat(rematch.get("payload").get("appliedMappings").get(0).get("purchaseOrderLineId").asText())
                .isEqualTo("POL-1001-1");
    }

    @Test
    void effectiveMappingIsRejectedWhenTheSameLineItemChangesAfterRefresh() throws Exception {
        String caseId = submittedCase(1, "Premium Copy Paper A4", 60, 2500, null);
        runMatch(caseId, "match-1");
        JsonNode first = freeze(caseId, "snap-1");
        JsonNode mapping = recordMapping(caseId, "map-1", currentCaseVersion(caseId),
                first.get("id").asText(), first.get("payloadHash").asText(), 1, ITEM_A, 200);
        String staleSubjectId = mapping.get("successorSnapshot").get("id").asText();
        String staleSubjectHash = mapping.get("successorSnapshot").get("payloadHash").asText();

        // The same purchase order line id remains, but its item changed A -> B.
        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .snapshotVersion(6)
                        .purchaseOrderVersion(4)
                        .clearLines()
                        .addLine("POL-1001-1", "ITEM-A4-90", "Premium Copy Paper A4 90g", 100, 2500)
                        .addLine("POL-1001-2", ITEM_B, "Laser Toner Black", 20, 55000)
                        .toJson());
        refreshSnapshot();

        JsonNode rematch = runMatch(caseId, "match-2");
        JsonNode line = rematch.get("payload").get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(line.get("purchaseOrderLine").isNull()).isTrue();
        JsonNode exception = rematch.get("payload").get("exceptions").get(0);
        assertThat(exception.get("details").get("mappedPurchaseOrderLineId").asText()).isEqualTo("POL-1001-1");
        assertThat(exception.get("details").get("currentPurchaseOrderLineItemId").asText()).isEqualTo("ITEM-A4-90");
        assertThat(rematch.get("payload").get("appliedMappings").get(0).get("purchaseOrderLineId").asText())
                .isEqualTo("POL-1001-1");

        // The subject the human saw is now stale; no decision can target it.
        MvcResult staleAction = supplementRaw(caseId, "supp-1", currentCaseVersion(caseId),
                staleSubjectId, staleSubjectHash, "reason");
        assertThat(staleAction.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(staleAction).get("code").asText()).isEqualTo("STALE_REVIEW_TARGET");

        // A fresh subject still shows the line as an exception, never MATCHED.
        JsonNode frozen = freeze(caseId, "snap-2");
        assertThat(frozen.get("payload").get("matchResult").get("payload")
                        .get("lineOutcomes").get(0).get("status").asText())
                .isEqualTo("EVIDENCE_INSUFFICIENT");

        // Only a new human mapping to the corrected item yields a MATCHED successor.
        JsonNode remapped = recordMapping(caseId, "map-2", currentCaseVersion(caseId),
                frozen.get("id").asText(), frozen.get("payloadHash").asText(), 1, "ITEM-A4-90", 200);
        assertThat(remapped.get("decision").get("mappingItemId").asText()).isEqualTo("ITEM-A4-90");
        assertThat(remapped.get("decision").get("mappingPoLineId").asText()).isEqualTo("POL-1001-1");
        assertThat(remapped.get("successorSnapshot").get("payload").get("matchResult").get("payload")
                        .get("lineOutcomes").get(0).get("status").asText())
                .isEqualTo("MATCHED");
    }

    @Test
    void duplicateRequestReplaysAndDifferentPayloadConflicts() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");

        JsonNode first = freeze(caseId, "snap-1");
        JsonNode replay = freeze(caseId, "snap-1");
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(count("review_snapshot")).isEqualTo(1);

        MvcResult conflict = mockMvc.perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody("snap-1", currentCaseVersion(caseId) + 100)))
                .andReturn();
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(conflict).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void supplementHappyPathTransitionsCaseAndBlocksFurtherReview() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        JsonNode decision = supplement(caseId, "supp-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), "need a corrected invoice");
        assertThat(decision.get("decision").asText()).isEqualTo("SUPPLEMENT_REQUESTED");
        assertThat(decision.get("decisionNumber").asInt()).isEqualTo(1);
        assertThat(decision.get("reason").asText()).isEqualTo("need a corrected invoice");
        assertThat(currentStatus(caseId)).isEqualTo("SUPPLEMENT_REQUIRED");

        assertThat(runMatchRaw(caseId, "match-2").getResponse().getStatus()).isEqualTo(409);
        assertThat(freezeRaw(caseId, "snap-2", currentCaseVersion(caseId)).getResponse().getStatus()).isEqualTo(409);
        assertThat(supplementRaw(caseId, "supp-2", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), "again").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void rejectHappyPathTransitionsCaseToRejected() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        JsonNode decision = reject(caseId, "rej-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), "not a valid claim");
        assertThat(decision.get("decision").asText()).isEqualTo("REJECTED");
        assertThat(decision.get("decisionNumber").asInt()).isEqualTo(1);
        assertThat(currentStatus(caseId)).isEqualTo("REJECTED");

        assertThat(supplementRaw(caseId, "supp-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), "too late").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void humanActionsRejectWrongVersionHashSnapshotAndReason() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");
        long version = currentCaseVersion(caseId);

        MvcResult staleVersion = supplementRaw(caseId, "s-1", version + 5,
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), "reason");
        assertThat(staleVersion.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(staleVersion).get("code").asText()).isEqualTo("STALE_CASE_VERSION");

        MvcResult unknownSnapshot = supplementRaw(caseId, "s-2", version,
                UUID.randomUUID().toString(), snapshot.get("payloadHash").asText(), "reason");
        assertThat(unknownSnapshot.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(unknownSnapshot).get("code").asText()).isEqualTo("REVIEW_SNAPSHOT_NOT_FOUND");

        MvcResult wrongHash = supplementRaw(caseId, "s-3", version,
                snapshot.get("id").asText(), "deadbeef", "reason");
        assertThat(wrongHash.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(wrongHash).get("code").asText()).isEqualTo("REVIEW_STATE_CONFLICT");

        MvcResult blankReason = supplementRaw(caseId, "s-4", version,
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), "   ");
        assertThat(blankReason.getResponse().getStatus()).isEqualTo(400);

        assertThat(count("review_decision")).isZero();
        assertThat(currentStatus(caseId)).isEqualTo("REVIEW_PENDING");
    }

    @Test
    void freshnessReportsCaseVersionAndStateChanges() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        freeze(caseId, "snap-1");

        jdbc.update("update invoice_case set version = version + 1,"
                + " updated_at = updated_at + interval '1 second' where id = ?", UUID.fromString(caseId));
        JsonNode versionStale = freshness(caseId, 1);
        assertThat(reasonNames(versionStale)).contains("CASE_VERSION");
        assertThat(versionStale.get("current").asBoolean()).isFalse();

        jdbc.update("update invoice_case set status = 'SUPPLEMENT_REQUIRED', version = version + 1,"
                + " updated_at = updated_at + interval '1 second' where id = ?", UUID.fromString(caseId));
        JsonNode stateStale = freshness(caseId, 1);
        assertThat(reasonNames(stateStale)).contains("CASE_STATE", "CASE_VERSION");
    }

    @Test
    void freshnessReportsNewMatchResult() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        freeze(caseId, "snap-1");

        runMatch(caseId, "match-2");

        assertThat(reasonNames(freshness(caseId, 1))).contains("MATCH_RESULT");
    }

    @Test
    void freshnessReportsNewEvidenceBundle() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        JsonNode snapshot = freeze(caseId, "snap-1");

        supplement(caseId, "supp-1", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText(), "correct evidence");
        openRevision(caseId, "rev-1", currentCaseVersion(caseId));
        submit(caseId, "submit-2", currentCaseVersion(caseId));

        assertThat(reasonNames(freshness(caseId, 1))).contains("EVIDENCE_BUNDLE");
    }

    @Test
    void freshnessReportsPurchasingSnapshotChange() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");
        freeze(caseId, "snap-1");

        STUB.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .snapshotVersion(6)
                        .purchaseOrderVersion(4)
                        .toJson());
        purchasingReferenceService.refresh(
                new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of(SUPPLIER)));

        JsonNode stale = freshness(caseId, 1);
        assertThat(reasonNames(stale)).contains("PURCHASING_SNAPSHOT");
    }

    @Test
    void snapshotAndDecisionNumbersAreMonotonic() throws Exception {
        String caseId = submittedCase(1, "A4 Paper", 60, 2500, ITEM_A);
        runMatch(caseId, "match-1");

        JsonNode first = freeze(caseId, "snap-1");
        JsonNode second = freeze(caseId, "snap-2");
        assertThat(first.get("snapshotNumber").asInt()).isEqualTo(1);
        assertThat(second.get("snapshotNumber").asInt()).isEqualTo(2);

        JsonNode mapping = recordMapping(caseId, "map-1", currentCaseVersion(caseId),
                second.get("id").asText(), second.get("payloadHash").asText(), 1, ITEM_B, 200);
        JsonNode supplement = supplement(caseId, "supp-1", currentCaseVersion(caseId),
                mapping.get("successorSnapshot").get("id").asText(),
                mapping.get("successorSnapshot").get("payloadHash").asText(), "reason");
        assertThat(mapping.get("decision").get("decisionNumber").asInt()).isEqualTo(1);
        assertThat(supplement.get("decisionNumber").asInt()).isEqualTo(2);
    }

    @Test
    void overflowingLineTotalIsRejectedAtomically() throws Exception {
        String caseId = submittedCase(1, "Very Expensive", 2, Long.MAX_VALUE, ITEM_A);
        runMatch(caseId, "match-1");

        MvcResult result = freezeRaw(caseId, "snap-1", currentCaseVersion(caseId));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(result).get("code").asText()).isEqualTo("NUMERIC_OVERFLOW");
        assertThat(count("review_snapshot")).isZero();
        assertThat(count("review_decision")).isZero();
    }

    private String submittedCase(int lineNumber, String itemName, int quantity, long unitPrice, String confirmedItemId)
            throws Exception {
        JsonNode created = createCase("create-1");
        String caseId = created.get("id").asText();
        replaceDraft(caseId, "draft-1", created.get("version").asLong(),
                List.of(line(lineNumber, itemName, quantity, unitPrice, confirmedItemId)));
        submit(caseId, "submit-1", currentCaseVersion(caseId));
        return caseId;
    }

    private JsonNode runMatch(String caseId, String requestId) throws Exception {
        MvcResult result = runMatchRaw(caseId, requestId);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private MvcResult runMatchRaw(String caseId, String requestId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        return mockMvc.perform(post("/api/invoice-cases/{id}/match", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode freeze(String caseId, String requestId) throws Exception {
        MvcResult result = freezeRaw(caseId, requestId, currentCaseVersion(caseId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private MvcResult freezeRaw(String caseId, String requestId, long expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(requestId, expectedVersion)))
                .andReturn();
    }

    private String freezeBody(String requestId, long expectedVersion) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode recordMapping(
            String caseId, String requestId, long expectedVersion, String snapshotId, String hash,
            int lineNumber, String itemId, int expectedStatus) throws Exception {
        MvcResult result = recordMappingRaw(caseId, requestId, expectedVersion, snapshotId, hash, lineNumber, itemId);
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return read(result);
    }

    private MvcResult recordMappingRaw(
            String caseId, String requestId, long expectedVersion, String snapshotId, String hash,
            int lineNumber, String itemId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        body.put("reviewSnapshotId", snapshotId);
        body.put("reviewPayloadHash", hash);
        body.put("lineNumber", lineNumber);
        body.put("itemId", itemId);
        return mockMvc.perform(post("/api/invoice-cases/{id}/mapping-decisions", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode supplement(
            String caseId, String requestId, long expectedVersion, String snapshotId, String hash, String reason)
            throws Exception {
        MvcResult result = supplementRaw(caseId, requestId, expectedVersion, snapshotId, hash, reason);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private MvcResult supplementRaw(
            String caseId, String requestId, long expectedVersion, String snapshotId, String hash, String reason)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        body.put("reviewSnapshotId", snapshotId);
        body.put("reviewPayloadHash", hash);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/invoice-cases/{id}/supplement-requests", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode reject(
            String caseId, String requestId, long expectedVersion, String snapshotId, String hash, String reason)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        body.put("reviewSnapshotId", snapshotId);
        body.put("reviewPayloadHash", hash);
        body.put("reason", reason);
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/reject", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private void openRevision(String caseId, String requestId, long expectedVersion) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", expectedVersion);
        MvcResult result = mockMvc.perform(post("/api/invoice-cases/{id}/revisions", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private JsonNode createCase(String requestId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", "INV-1");
        MvcResult result = mockMvc.perform(post("/api/invoice-cases")
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
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

    private JsonNode getHistory(String caseId) throws Exception {
        return read(mockMvc.perform(get("/api/invoice-cases/{id}/review-snapshots", caseId)).andReturn());
    }

    private JsonNode latestSnapshot(String caseId) throws Exception {
        return read(mockMvc.perform(get("/api/invoice-cases/{id}/review-snapshots/latest", caseId)).andReturn());
    }

    private void refreshSnapshot() {
        purchasingReferenceService.refresh(
                new RefreshPurchaseOrderCommand(PurchaseOrderId.of(PO_ID), SupplierId.of(SUPPLIER)));
    }

    private JsonNode getEvidenceBundle(String caseId, int version) throws Exception {
        return read(mockMvc.perform(get("/api/invoice-cases/{id}/evidence-bundles/{version}", caseId, version))
                .andReturn());
    }

    private JsonNode getDecisions(String caseId) throws Exception {
        return read(mockMvc.perform(get("/api/invoice-cases/{id}/review-decisions", caseId)).andReturn());
    }

    private JsonNode freshness(String caseId, int snapshotNumber) throws Exception {
        return read(mockMvc.perform(
                        get("/api/invoice-cases/{id}/review-snapshots/{number}/freshness", caseId, snapshotNumber))
                .andReturn());
    }

    private List<String> reasonNames(JsonNode freshness) {
        List<String> names = new java.util.ArrayList<>();
        freshness.get("reasons").forEach(node -> names.add(node.asText()));
        return names;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
