package com.invoicematch.core.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.approval.application.ApprovalApplicationService;
import com.invoicematch.core.approval.application.ApprovalResult;
import com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.application.ReviewService;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import com.invoicematch.core.support.TestActors;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Adversarial P1-07 tests: a stored review snapshot / match result / evidence
 * bundle that does not match an independent reconstruction must be rejected with
 * zero effects, even when every relational row is present and the stored hashes
 * are self-consistent with forged payloads.
 */
class ApprovalForgeryIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private ApprovalApplicationService approvals;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private InvoiceCaseApplicationService invoiceCaseCommands;

    @Autowired
    private InvoiceCaseRepository invoiceCases;

    @Autowired
    private ReviewSnapshotRepository reviewSnapshots;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
    }

    @Test
    void forgedMatchResultPayloadAndHashAreRejected() {
        UUID caseId = submittedCase();
        runMatch(caseId);
        jdbc.update(
                "insert into match_result (id, invoice_case_id, evidence_bundle_id, result_number, result_hash,"
                        + " purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark, payload, created_at)"
                        + " select ?, invoice_case_id, evidence_bundle_id, 2, 'deadbeef',"
                        + " purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark, payload, now()"
                        + " from match_result where invoice_case_id = ? and result_number = 1",
                UUID.randomUUID(),
                caseId);
        ReviewSnapshot snapshot = freeze(caseId);

        Object result = attempt(caseId, snapshot);

        assertThat(result).isInstanceOf(RuntimeException.class);
        assertNoApprovalEffects(caseId);
    }

    @Test
    void forgedSnapshotHashIsRejected() {
        UUID caseId = submittedCase();
        runMatch(caseId);
        ReviewSnapshot real = freeze(caseId);
        jdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                        + " match_result_number, snapshot_number, target_case_version,"
                        + " target_evidence_bundle_version, purchasing_snapshot_version, purchasing_snapshot_hash,"
                        + " mapping_watermark, payload_hash, payload, created_at)"
                        + " select ?, invoice_case_id, evidence_bundle_id, match_result_id, match_result_number, 2,"
                        + " target_case_version, target_evidence_bundle_version, purchasing_snapshot_version,"
                        + " purchasing_snapshot_hash, mapping_watermark, 'deadbeef', cast('{}' as jsonb), now()"
                        + " from review_snapshot where id = ?",
                UUID.randomUUID(),
                real.id());
        ReviewSnapshot forged = latestSnapshot(caseId);

        Object result = attempt(caseId, forged);

        assertThat(result).isInstanceOf(RuntimeException.class);
        assertNoApprovalEffects(caseId);
    }

    @Test
    void forgedSnapshotPayloadContentIsRejected() {
        UUID caseId = submittedCase();
        runMatch(caseId);
        ReviewSnapshot real = freeze(caseId);
        String forgedPayload = "{\"schemaVersion\":\"review-snapshot-v1\",\"caseId\":\"" + caseId
                + "\",\"invoiceLines\":[{\"lineNumber\":1,\"quantity\":1,\"unitPrice\":1,\"lineAmount\":1}],"
                + "\"totalAmount\":1,\"matchResult\":{\"payload\":{\"normal\":true}},"
                + "\"arbitraryReceiptId\":\"FAKE\",\"receiptDate\":\"1999-01-01\"}";
        jdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                        + " match_result_number, snapshot_number, target_case_version,"
                        + " target_evidence_bundle_version, purchasing_snapshot_version, purchasing_snapshot_hash,"
                        + " mapping_watermark, payload_hash, payload, created_at)"
                        + " select ?, invoice_case_id, evidence_bundle_id, match_result_id, match_result_number, 2,"
                        + " target_case_version, target_evidence_bundle_version, purchasing_snapshot_version,"
                        + " purchasing_snapshot_hash, mapping_watermark, payload_hash, cast(? as jsonb), now()"
                        + " from review_snapshot where id = ?",
                UUID.randomUUID(),
                forgedPayload,
                real.id());
        ReviewSnapshot forged = latestSnapshot(caseId);

        Object result = attempt(caseId, forged);

        assertThat(result).isInstanceOf(RuntimeException.class);
        assertNoApprovalEffects(caseId);
    }

    @Test
    void forgedV2SnapshotPayloadContentIsRejected() throws Exception {
        UUID caseId = submittedCase();
        runMatch(caseId);
        ReviewSnapshot real = freeze(caseId);
        String storedPayload = jdbc.queryForObject(
                "select payload::text from review_snapshot where id = ?", String.class, real.id());
        ObjectNode tampered = (ObjectNode) objectMapper.readTree(storedPayload);
        tampered.put("totalAmount", 1L);
        insertSnapshotCopy(caseId, real.id(), tampered.toString(), real.payloadHash());
        ReviewSnapshot forged = latestSnapshot(caseId);

        Object result = attempt(caseId, forged);

        assertThat(result).isInstanceOf(RuntimeException.class);
        assertNoApprovalEffects(caseId);
    }

    @Test
    void unknownSnapshotSchemaVersionFailsClosed() {
        UUID caseId = submittedCase();
        runMatch(caseId);
        ReviewSnapshot real = freeze(caseId);
        String storedPayload = jdbc.queryForObject(
                "select payload::text from review_snapshot where id = ?", String.class, real.id());
        String unknown = storedPayload.replace("\"review-snapshot-v2\"", "\"review-snapshot-v9\"");
        insertSnapshotCopy(caseId, real.id(), unknown, real.payloadHash());
        ReviewSnapshot forged = latestSnapshot(caseId);

        Object result = attempt(caseId, forged);

        assertThat(result).isInstanceOf(RuntimeException.class);
        assertNoApprovalEffects(caseId);
    }

    private void insertSnapshotCopy(UUID caseId, UUID sourceId, String payload, String payloadHash) {
        jdbc.update(
                "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                        + " match_result_number, snapshot_number, target_case_version,"
                        + " target_evidence_bundle_version, purchasing_snapshot_version, purchasing_snapshot_hash,"
                        + " mapping_watermark, payload_hash, payload, created_at)"
                        + " select ?, invoice_case_id, evidence_bundle_id, match_result_id, match_result_number, 2,"
                        + " target_case_version, target_evidence_bundle_version, purchasing_snapshot_version,"
                        + " purchasing_snapshot_hash, mapping_watermark, ?, cast(? as jsonb), now()"
                        + " from review_snapshot where id = ?",
                UUID.randomUUID(),
                payloadHash,
                payload,
                sourceId);
    }

    @Test
    void forgedEvidenceBundlePayloadHashIsRejected() {
        UUID caseId = submittedCase();
        UUID realBundle = jdbc.queryForObject(
                "select id from evidence_bundle where invoice_case_id = ? and version_number = 1",
                UUID.class,
                caseId);
        jdbc.update(
                "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                        + " payload_hash, payload, submitted_at)"
                        + " select ?, invoice_case_id, draft_revision_id, 2, 'deadbeef', payload, now()"
                        + " from evidence_bundle where id = ?",
                UUID.randomUUID(),
                realBundle);
        runMatch(caseId);
        ReviewSnapshot snapshot = freeze(caseId);

        Object result = attempt(caseId, snapshot);

        assertThat(result).isInstanceOf(RuntimeException.class);
        assertNoApprovalEffects(caseId);
    }

    private Object attempt(UUID caseId, ReviewSnapshot snapshot) {
        long version = invoiceCases.findById(caseId).orElseThrow().version();
        try {
            return TestActors.call("approver", "APPROVER", () -> approvals
                    .approve(new ApproveInvoiceCaseCommand(
                            caseId, "approve-" + snapshot.id(), version, snapshot.id(), snapshot.payloadHash()))
                    .body());
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void assertNoApprovalEffects(UUID caseId) {
        assertThat(jdbc.queryForObject("select count(*) from receipt_allocation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from review_decision where invoice_case_id = ? and decision = 'APPROVED'",
                        Integer.class,
                        caseId))
                .isZero();
        assertThat(invoiceCases.findById(caseId).orElseThrow().status().name()).isEqualTo("REVIEW_PENDING");
    }

    private UUID submittedCase() {
        return TestActors.call("submitter", "SUBMITTER", () -> {
            UUID id = invoiceCaseCommands
                    .create(new CreateInvoiceCaseCommand("create-1", SUPPLIER, PO_ID, "INV-1"))
                    .body()
                    .id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    id,
                    "draft-1",
                    version(id),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", 60, 2500, ITEM_A))));
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(id, "submit-1", version(id)));
            return id;
        });
    }

    private void runMatch(UUID caseId) {
        TestActors.run("operator", "OPERATOR", () -> matchingService.run(new RunMatchCommand(caseId, "match-1")));
    }

    private ReviewSnapshot freeze(UUID caseId) {
        TestActors.run("approver", "APPROVER", () -> reviewService.freezeSnapshot(
                new FreezeReviewSnapshotCommand(caseId, "snap-1", version(caseId))));
        return latestSnapshot(caseId);
    }

    private ReviewSnapshot latestSnapshot(UUID caseId) {
        return reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId).orElseThrow();
    }

    private long version(UUID caseId) {
        return invoiceCases.findById(caseId).orElseThrow().version();
    }
}
