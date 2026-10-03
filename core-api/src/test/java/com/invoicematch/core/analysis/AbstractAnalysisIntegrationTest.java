package com.invoicematch.core.analysis;

import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.OpenSupplementRevisionCommand;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.application.RequestSupplementCommand;
import com.invoicematch.core.review.application.ReviewService;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import com.invoicematch.core.support.TestActors;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared fixture for the P2-05 analysis-reservation tests: a real PostgreSQL
 * database, a stub purchasing system, and helpers that create a draft case,
 * seed a completed document reference without MinIO, submit, and advance a case
 * to a supplement draft so a second evidence version can be submitted.
 */
abstract class AbstractAnalysisIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final String SUPPLIER = "SUP-1";
    protected static final String PO_ID = "PO-1001";
    protected static final String ITEM_A = "ITEM-A4-80";

    private static final StubPurchasingServer PURCHASING;

    static {
        try {
            PURCHASING = new StubPurchasingServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void purchasingProperties(DynamicPropertyRegistry registry) {
        registry.add("purchasing-system.base-url", PURCHASING::baseUrl);
        registry.add("purchasing-system.connect-timeout", () -> "1s");
        registry.add("purchasing-system.read-timeout", () -> "1s");
    }

    @Autowired
    protected InvoiceCaseApplicationService invoiceCaseCommands;
    @Autowired
    protected MatchingService matchingService;
    @Autowired
    protected ReviewService reviewService;
    @Autowired
    protected InvoiceCaseRepository invoiceCases;
    @Autowired
    protected ReviewSnapshotRepository reviewSnapshots;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetAnalysisDatabase() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        PURCHASING.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
    }

    /** Creates a DRAFT case with one line, without submitting it. */
    protected UUID createDraftCase(String invoiceNumber) {
        return TestActors.call("submitter", "SUBMITTER", () -> {
            UUID id = invoiceCaseCommands
                    .create(new CreateInvoiceCaseCommand(
                            "create-" + invoiceNumber, SUPPLIER, PO_ID, invoiceNumber))
                    .body()
                    .id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    id,
                    "draft-" + invoiceNumber,
                    caseVersion(id),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", 60, 2500, ITEM_A))));
            return id;
        });
    }

    protected void submit(UUID caseId, String requestId) {
        submit(caseId, requestId, caseVersion(caseId));
    }

    protected void submit(UUID caseId, String requestId, long expectedCaseVersion) {
        TestActors.run("submitter", "SUBMITTER", () -> invoiceCaseCommands.submit(
                new SubmitInvoiceCaseCommand(caseId, requestId, expectedCaseVersion)));
    }

    /**
     * Seeds a completed document reference into the current open draft through
     * raw SQL, bypassing MinIO, and returns the document id. The row set is
     * exactly what {@code DocumentStore.evidenceForRevision} reads.
     */
    protected UUID seedCompletedDocument(UUID caseId) {
        UUID revision = currentDraftRevisionId(caseId);
        UUID documentId = UUID.randomUUID();
        String checksum = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        jdbc.update("insert into document_upload (id, invoice_case_id, draft_revision_id, file_name, media_type,"
                        + " size_bytes, checksum, upload_key, expires_at, created_at)"
                        + " values (?, ?, ?, 'invoice.pdf', 'application/pdf', 4, ?, ?,"
                        + " now() + interval '10 minutes', now())",
                documentId, caseId, revision, checksum, "uploads/" + documentId);
        jdbc.update("insert into document (id, object_key, registered_case_version, registered_at)"
                        + " values (?, ?, 0, now())",
                documentId, "originals/" + caseId + "/" + documentId);
        jdbc.update("insert into draft_revision_document (draft_revision_id, document_id, invoice_case_id, created_at)"
                        + " values (?, ?, ?, now())",
                revision, documentId, caseId);
        return documentId;
    }

    /** Runs the approval-side supplement request and opens the next draft. */
    protected void advanceToSupplementDraft(UUID caseId, String tag) {
        TestActors.run("operator", "OPERATOR", () ->
                matchingService.run(new RunMatchCommand(caseId, "match-" + tag)));
        TestActors.run("approver", "APPROVER", () ->
                reviewService.freezeSnapshot(
                        new FreezeReviewSnapshotCommand(caseId, "snapshot-" + tag, caseVersion(caseId))));
        ReviewSnapshot snapshot = reviewSnapshots
                .findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId)
                .orElseThrow();
        TestActors.run("approver", "APPROVER", () ->
                reviewService.requestSupplement(new RequestSupplementCommand(
                        caseId,
                        "supplement-" + tag,
                        caseVersion(caseId),
                        snapshot.id(),
                        snapshot.payloadHash(),
                        "need the corrected document")));
        TestActors.run("submitter", "SUBMITTER", () ->
                invoiceCaseCommands.openSupplementRevision(
                        new OpenSupplementRevisionCommand(caseId, "revision-" + tag, caseVersion(caseId))));
    }

    protected long caseVersion(UUID caseId) {
        return invoiceCases.findById(caseId).orElseThrow().version();
    }

    protected UUID currentDraftRevisionId(UUID caseId) {
        return jdbc.queryForObject(
                "select current_draft_revision_id from invoice_case where id = ?", UUID.class, caseId);
    }

    protected Map<String, Object> bundle(UUID caseId, int versionNumber) {
        return jdbc.queryForMap(
                "select id, payload_hash, payload_schema from evidence_bundle"
                        + " where invoice_case_id = ? and version_number = ?",
                caseId,
                versionNumber);
    }

    protected Map<String, Object> run(UUID caseId, int inputVersion) {
        return jdbc.queryForMap(
                "select id, invoice_case_id, evidence_bundle_id, input_version, evidence_payload_hash,"
                        + " workflow_version, status, created_at, updated_at"
                        + " from analysis_run where invoice_case_id = ? and input_version = ?",
                caseId,
                inputVersion);
    }

    protected Map<String, Object> request(UUID caseId, int inputVersion) {
        return jdbc.queryForMap(
                "select o.id, o.analysis_run_id, o.schema_version, o.payload::text as payload, o.status, o.created_at"
                        + " from analysis_request_outbox o"
                        + " join analysis_run r on r.id = o.analysis_run_id"
                        + " where r.invoice_case_id = ? and r.input_version = ?",
                caseId,
                inputVersion);
    }

    protected int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
