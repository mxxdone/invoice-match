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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /** The frozen metadata of one document a fixture seeded. */
    protected record SeededDocument(
            UUID documentId,
            UUID sourceDraftRevisionId,
            String fileName,
            String mediaType,
            long sizeBytes,
            String checksum) {
    }

    /** A storage-valid frozen bundle a fixture seeded directly. */
    protected record FrozenBundle(UUID bundleId, int versionNumber, String payloadHash) {
    }

    /**
     * Seeds a completed document reference into the current open draft through
     * raw SQL, bypassing MinIO. The row set is exactly what
     * {@code DocumentStore.evidenceForRevision} reads.
     */
    protected SeededDocument seedDocument(
            UUID caseId, UUID documentId, String fileName, long sizeBytes, String checksum) {
        UUID revision = currentDraftRevisionId(caseId);
        String mediaType = "application/pdf";
        jdbc.update("insert into document_upload (id, invoice_case_id, draft_revision_id, file_name, media_type,"
                        + " size_bytes, checksum, upload_key, expires_at, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, now() + interval '10 minutes', now())",
                documentId, caseId, revision, fileName, mediaType, sizeBytes, checksum, "uploads/" + documentId);
        jdbc.update("insert into document (id, object_key, registered_case_version, registered_at)"
                        + " values (?, ?, 0, now())",
                documentId, "originals/" + caseId + "/" + documentId);
        jdbc.update("insert into draft_revision_document (draft_revision_id, document_id, invoice_case_id, created_at)"
                        + " values (?, ?, ?, now())",
                revision, documentId, caseId);
        return new SeededDocument(documentId, revision, fileName, mediaType, sizeBytes, checksum);
    }

    /** Seeds one completed document with generated identity and metadata. */
    protected UUID seedCompletedDocument(UUID caseId) {
        String checksum = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        return seedDocument(caseId, UUID.randomUUID(), "invoice.pdf", 4, checksum).documentId();
    }

    /**
     * Seeds a storage-valid, document-less (legacy) frozen bundle for the same
     * case: the next sealed draft revision plus a legacy evidence bundle. It
     * writes no document references, so it never disturbs the normal supplement
     * document inheritance.
     */
    protected FrozenBundle seedLegacyFrozenBundle(UUID caseId, String invoiceNumber, int versionNumber) {
        UUID revisionId = UUID.randomUUID();
        UUID bundleId = UUID.randomUUID();
        String payload = "{\"caseId\":\"" + caseId + "\",\"supplierId\":\"" + SUPPLIER + "\","
                + "\"purchaseOrderId\":\"" + PO_ID + "\",\"invoiceNumber\":\"" + invoiceNumber + "\","
                + "\"revisionNumber\":" + versionNumber + ",\"lines\":[]}";
        String payloadHash = sha256Hex(payload);
        jdbc.update("insert into draft_revision (id, invoice_case_id, revision_number, status, created_at, sealed_at)"
                        + " values (?, ?, ?, 'SEALED', now(), now())",
                revisionId, caseId, versionNumber);
        jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                        + " payload_schema, payload_hash, payload, submitted_at)"
                        + " values (?, ?, ?, ?, 'legacy-v1', ?, cast(? as jsonb), now())",
                bundleId, caseId, revisionId, versionNumber, payloadHash, payload);
        return new FrozenBundle(bundleId, versionNumber, payloadHash);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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
                "select id, payload_hash, payload_schema, payload::text as payload from evidence_bundle"
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
