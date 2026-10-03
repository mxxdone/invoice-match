package com.invoicematch.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.AnalysisClaimCommand;
import com.invoicematch.core.analysis.application.AnalysisDocumentResultCommand;
import com.invoicematch.core.analysis.application.AnalysisExecutionService;
import com.invoicematch.core.analysis.application.AnalysisHeartbeatCommand;
import com.invoicematch.core.analysis.application.ClaimOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared fixture for the P2-07 execution tests: real PostgreSQL, analysis
 * reservation enabled, and a machine surface enabled with a 40-character token.
 * Helpers create a submitted document-bearing case and build machine commands
 * from the authoritative run and frozen manifest.
 */
abstract class AbstractAnalysisExecutionIntegrationTest extends AbstractAnalysisIntegrationTest {

    protected static final String WORKER_TOKEN = "p2-07-worker-token-0123456789abcdefghijklmnop";

    @DynamicPropertySource
    static void executionProperties(DynamicPropertyRegistry registry) {
        registry.add("analysis.request.enabled", () -> true);
        registry.add("analysis.worker.enabled", () -> true);
        registry.add("analysis.worker.token", () -> WORKER_TOKEN);
    }

    @Autowired
    protected AnalysisExecutionService execution;
    @Autowired
    protected ObjectMapper json;

    /** One submitted case with its reserved run and connected request. */
    protected record RunFixture(
            UUID caseId,
            UUID runId,
            UUID eventId,
            int inputVersion,
            String evidencePayloadHash,
            String workflowVersion,
            List<SeededDocument> documents) {
    }

    /** Creates a case with {@code documentCount} PDF documents and submits it. */
    protected RunFixture preparePdfRun(int documentCount) {
        UUID caseId = createDraftCase("INV-EXEC-" + UUID.randomUUID());
        List<SeededDocument> documents = new ArrayList<>();
        for (int i = 0; i < documentCount; i++) {
            documents.add(seedDocument(
                    caseId,
                    UUID.randomUUID(),
                    "invoice-" + i + ".pdf",
                    "application/pdf",
                    12,
                    checksum(i)));
        }
        submit(caseId, "submit-" + caseId);
        return fixture(caseId, documents);
    }

    protected RunFixture fixture(UUID caseId, List<SeededDocument> documents) {
        Map<String, Object> run = run(caseId, 1);
        Map<String, Object> request = request(caseId, 1);
        return new RunFixture(
                caseId,
                (UUID) run.get("id"),
                (UUID) request.get("id"),
                1,
                (String) run.get("evidence_payload_hash"),
                (String) run.get("workflow_version"),
                documents);
    }

    protected AnalysisClaimCommand claimCommand(RunFixture fixture) {
        return new AnalysisClaimCommand(
                fixture.eventId(), fixture.inputVersion(), fixture.evidencePayloadHash(), fixture.workflowVersion());
    }

    protected AnalysisHeartbeatCommand heartbeatCommand(RunFixture fixture, UUID token) {
        return new AnalysisHeartbeatCommand(token, fixture.inputVersion(), fixture.evidencePayloadHash());
    }

    protected AnalysisDocumentResultCommand resultCommand(
            RunFixture fixture, UUID token, UUID documentId, String outcome, JsonNode result, String errorCode) {
        return new AnalysisDocumentResultCommand(
                token, fixture.inputVersion(), fixture.evidencePayloadHash(), documentId, outcome, result, errorCode);
    }

    protected ObjectNode pdfResult(SeededDocument document, String text) {
        return AnalysisResultFixtures.pdf(document.documentId(), document.sizeBytes(), document.checksum(), text);
    }

    protected ClaimOutcome.Claimed claim(RunFixture fixture) {
        ClaimOutcome outcome = execution.claim(fixture.runId(), claimCommand(fixture));
        if (outcome instanceof ClaimOutcome.Claimed claimed) {
            return claimed;
        }
        throw new IllegalStateException("expected CLAIMED but was " + outcome);
    }

    /** Seeds a completed document with an explicit media type. */
    protected SeededDocument seedDocument(
            UUID caseId, UUID documentId, String fileName, String mediaType, long sizeBytes, String checksum) {
        UUID revision = currentDraftRevisionId(caseId);
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

    private static String checksum(int seed) {
        return Integer.toHexString(seed + 1).repeat(64).substring(0, 64);
    }
}
