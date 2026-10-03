package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.application.AnalysisInput;
import com.invoicematch.core.analysis.application.AnalysisRequestPayloadFactory;
import com.invoicematch.core.analysis.application.AnalysisRequestService;
import com.invoicematch.core.analysis.domain.AnalysisRun;
import com.invoicematch.core.document.domain.DocumentEvidence;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.domain.StaleCaseVersionException;
import com.invoicematch.core.support.TestActors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P2-05 analysis reservation with {@code analysis.request.enabled=true} against
 * real PostgreSQL: an enabled document submission reserves exactly one run and
 * one canonical request; a superseding submission stales/cancels the lower
 * version (even when the new bundle is document-less); replays, audit failures
 * and concurrent submissions never duplicate a reservation; and the database
 * enforces identity, uniqueness, cross-case integrity and append-only inputs.
 */
class AnalysisRequestEnabledIntegrationTest extends AbstractAnalysisIntegrationTest {

    @DynamicPropertySource
    static void analysisEnabledProperties(DynamicPropertyRegistry registry) {
        registry.add("analysis.request.enabled", () -> true);
    }

    @Autowired
    ObjectMapper json;
    @Autowired
    AnalysisRequestService analysisRequests;
    @Autowired
    AnalysisRequestPayloadFactory payloadFactory;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void enabledDocumentSubmissionReservesRunAndRequestWithCanonicalPayload() {
        UUID caseId = createDraftCase("INV-E1");

        // Two documents with caller-controlled ids and metadata, inserted in
        // descending documentId order so an unsorted payload cannot pass by
        // accident: the canonical arrays must come out ascending instead.
        UUID highId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        UUID lowId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        SeededDocument high = seedDocument(caseId, highId, "second.pdf", 22, "b".repeat(64));
        SeededDocument low = seedDocument(caseId, lowId, "first.pdf", 11, "a".repeat(64));

        submit(caseId, "submit-e1");

        assertThat(count("analysis_run")).isEqualTo(1);
        assertThat(count("analysis_request_outbox")).isEqualTo(1);
        assertThat(count("outbox_event")).isZero();

        Map<String, Object> bundle = bundle(caseId, 1);
        Map<String, Object> run = run(caseId, 1);
        assertThat(run.get("status")).isEqualTo("QUEUED");
        assertThat(run.get("workflow_version")).isEqualTo("document-parser-v1");
        assertThat(run.get("input_version")).isEqualTo(1);
        assertThat(run.get("evidence_bundle_id")).isEqualTo(bundle.get("id"));
        assertThat(run.get("evidence_payload_hash")).isEqualTo(bundle.get("payload_hash"));

        Map<String, Object> request = request(caseId, 1);
        assertThat(request.get("status")).isEqualTo("READY");
        assertThat(request.get("schema_version")).isEqualTo("analysis-request-v1");
        assertThat(request.get("analysis_run_id")).isEqualTo(run.get("id"));

        JsonNode payload = readJson((String) request.get("payload"));
        assertThat(payload.get("eventId").asText()).isEqualTo(request.get("id").toString());
        assertThat(payload.get("analysisRunId").asText()).isEqualTo(run.get("id").toString());
        assertThat(payload.get("invoiceCaseId").asText()).isEqualTo(caseId.toString());
        assertThat(payload.get("evidenceBundleId").asText()).isEqualTo(bundle.get("id").toString());
        assertThat(payload.get("inputVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("evidencePayloadHash").asText()).isEqualTo(bundle.get("payload_hash"));
        assertThat(payload.get("workflowVersion").asText()).isEqualTo("document-parser-v1");

        // The Outbox documents must equal the actual frozen bundle documents
        // field for field, including checksum, in the same order.
        JsonNode frozenDocuments = readJson((String) bundle.get("payload")).get("documents");
        assertThat(frozenDocuments.size()).isEqualTo(2);
        JsonNode outboxDocuments = payload.get("documents");
        assertThat(outboxDocuments).as("outbox documents must equal the frozen bundle documents")
                .isEqualTo(frozenDocuments);
        assertThat(outboxDocuments.size()).isEqualTo(2);
        // Ascending documentId proves canonical ordering, not insertion order.
        assertThat(outboxDocuments.get(0).get("documentId").asText()).isEqualTo(lowId.toString());
        assertThat(outboxDocuments.get(1).get("documentId").asText()).isEqualTo(highId.toString());
        assertThat(outboxDocuments.get(0).get("documentId").asText())
                .isLessThan(outboxDocuments.get(1).get("documentId").asText());
        assertDocumentMetadata(outboxDocuments.get(0), low);
        assertDocumentMetadata(outboxDocuments.get(1), high);
        assertDocumentMetadata(frozenDocuments.get(0), low);
        assertDocumentMetadata(frozenDocuments.get(1), high);

        // The same run fed the documents in reverse order must serialize to the
        // identical JSON, proving the factory sorts rather than trusting input.
        List<DocumentEvidence> reversed = new ArrayList<>(List.of(toEvidence(high), toEvidence(low)));
        AnalysisRun canonicalRun = AnalysisRun.queue(
                (UUID) run.get("id"),
                caseId,
                (UUID) bundle.get("id"),
                1,
                (String) bundle.get("payload_hash"),
                Instant.now());
        JsonNode rebuilt = readJson(
                payloadFactory.canonicalPayload((UUID) request.get("id"), canonicalRun, reversed));
        assertThat(rebuilt).isEqualTo(payload);

        // No object key, URL, credential → the request is safe to publish.
        String raw = (String) request.get("payload");
        assertThat(raw).doesNotContain("uploads/", "originals/", "http", "X-Amz", "secret");
    }

    @Test
    void replayReturnsStoredSubmissionWithoutReservingAgain() {
        UUID caseId = createDraftCase("INV-E2");
        seedCompletedDocument(caseId);
        long version = caseVersion(caseId);
        submit(caseId, "submit-e2", version);
        submit(caseId, "submit-e2", version);

        assertThat(count("analysis_run")).isEqualTo(1);
        assertThat(count("analysis_request_outbox")).isEqualTo(1);
    }

    @Test
    void legacyDocumentLessSubmissionReservesNothing() {
        UUID caseId = createDraftCase("INV-E3");
        submit(caseId, "submit-e3");

        assertThat(count("analysis_run")).isZero();
        assertThat(count("analysis_request_outbox")).isZero();
    }

    @Test
    void auditFailureRollsBackReservationAndRetrySucceeds() {
        UUID caseId = createDraftCase("INV-E4");
        seedCompletedDocument(caseId);
        jdbc.execute("alter table audit_entry add constraint test_analysis_audit_failure"
                + " check (action <> 'CASE_SUBMITTED') not valid");
        try {
            assertThatThrownBy(() -> submit(caseId, "submit-e4")).isInstanceOf(RuntimeException.class);
            assertThat(count("analysis_run")).isZero();
            assertThat(count("analysis_request_outbox")).isZero();
            assertThat(count("evidence_bundle")).isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from idempotency_record where scope = 'invoice-case:submit'", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("alter table audit_entry drop constraint test_analysis_audit_failure");
        }
        submit(caseId, "submit-e4");
        assertThat(count("analysis_run")).isEqualTo(1);
        assertThat(count("analysis_request_outbox")).isEqualTo(1);
    }

    @Test
    void supplementStalesPriorRunAndCancelsRequestThenReservesTheNextVersion() {
        UUID caseId = createDraftCase("INV-E5");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e5-v1");
        UUID firstRunId = (UUID) run(caseId, 1).get("id");
        String firstHash = (String) run(caseId, 1).get("evidence_payload_hash");

        advanceToSupplementDraft(caseId, "e5");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e5-v2");

        assertThat(count("analysis_run")).isEqualTo(2);
        assertThat(count("analysis_request_outbox")).isEqualTo(2);
        assertThat(run(caseId, 1).get("status")).isEqualTo("STALE");
        assertThat(request(caseId, 1).get("status")).isEqualTo("CANCELLED");
        // Superseded input identity is preserved unchanged.
        assertThat(run(caseId, 1).get("id")).isEqualTo(firstRunId);
        assertThat(run(caseId, 1).get("evidence_payload_hash")).isEqualTo(firstHash);
        assertThat(run(caseId, 2).get("status")).isEqualTo("QUEUED");
        assertThat(request(caseId, 2).get("status")).isEqualTo("READY");
    }

    @Test
    void documentLessInputCancelsLowerReservationWithoutReserving() {
        UUID caseId = createDraftCase("INV-E6");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e6");
        assertThat(run(caseId, 1).get("status")).isEqualTo("QUEUED");

        // Prepare a real, storage-valid second evidence version for the same
        // case: its next SEALED revision plus a document-less (legacy) frozen
        // bundle. This is exactly the input a document-less newer submission
        // would produce and it leaves the normal supplement document
        // inheritance untouched.
        FrozenBundle legacyV2 = seedLegacyFrozenBundle(caseId, "INV-E6", 2);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                analysisRequests.onEvidenceSubmitted(new AnalysisInput(
                        caseId, legacyV2.bundleId(), legacyV2.versionNumber(), legacyV2.payloadHash(), List.of())));

        assertThat(count("analysis_run")).isEqualTo(1);
        assertThat(count("analysis_request_outbox")).isEqualTo(1);
        assertThat(run(caseId, 1).get("status")).isEqualTo("STALE");
        assertThat(request(caseId, 1).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void supplementAuditFailureRollsBackTheCancellationThenRetryStalesIt() {
        UUID caseId = createDraftCase("INV-E7");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e7-v1");
        advanceToSupplementDraft(caseId, "e7");
        seedCompletedDocument(caseId);
        jdbc.execute("alter table audit_entry add constraint test_analysis_supplement_audit_failure"
                + " check (action <> 'CASE_SUBMITTED') not valid");
        try {
            assertThatThrownBy(() -> submit(caseId, "submit-e7-v2")).isInstanceOf(RuntimeException.class);
            // The cancellation of the lower reservation rolled back with the
            // failed submission; the prior run/request are still live.
            assertThat(count("analysis_run")).isEqualTo(1);
            assertThat(count("analysis_request_outbox")).isEqualTo(1);
            assertThat(run(caseId, 1).get("status")).isEqualTo("QUEUED");
            assertThat(request(caseId, 1).get("status")).isEqualTo("READY");
        } finally {
            jdbc.execute("alter table audit_entry drop constraint test_analysis_supplement_audit_failure");
        }
        submit(caseId, "submit-e7-v2");
        assertThat(count("analysis_run")).isEqualTo(2);
        assertThat(count("analysis_request_outbox")).isEqualTo(2);
        assertThat(run(caseId, 1).get("status")).isEqualTo("STALE");
        assertThat(request(caseId, 1).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void concurrentSubmissionsWithDifferentRequestsReserveOnce() throws Exception {
        UUID caseId = createDraftCase("INV-E8");
        seedCompletedDocument(caseId);
        long version = caseVersion(caseId);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> first = () -> submissionOutcome(caseId, "concurrent-a", version, start);
            Callable<String> second = () -> submissionOutcome(caseId, "concurrent-b", version, start);
            Future<String> a = pool.submit(first);
            Future<String> b = pool.submit(second);
            start.countDown();
            List<String> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "STALE_CASE_VERSION");
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("analysis_run")).isEqualTo(1);
        assertThat(count("analysis_request_outbox")).isEqualTo(1);
    }

    @Test
    void databaseRejectsCrossCaseAndWrongVersionRun() {
        UUID caseA = createDraftCase("INV-E9A");
        seedCompletedDocument(caseA);
        submit(caseA, "submit-e9a");
        UUID bundleA = (UUID) bundle(caseA, 1).get("id");

        UUID caseB = createDraftCase("INV-E9B");

        // The bundle belongs to case A but claims case B: composite FK rejects.
        assertDatabaseRejects("23503", () -> insertRun(caseB, bundleA, 1, "h"));
        // Case A has no evidence bundle version 2: composite FK rejects.
        assertDatabaseRejects("23503", () -> insertRun(caseA, bundleA, 2, "h"));
    }

    @Test
    void databaseRejectsDuplicateRunAndRequest() {
        UUID caseId = createDraftCase("INV-E10");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e10");
        UUID runId = (UUID) run(caseId, 1).get("id");
        UUID bundleId = (UUID) bundle(caseId, 1).get("id");

        assertDatabaseRejects("23505", () -> insertRun(caseId, bundleId, 1, "h"));
        assertDatabaseRejects("23505", () -> jdbc.update(
                "insert into analysis_request_outbox"
                        + " (id, analysis_run_id, schema_version, payload, status, created_at)"
                        + " values (?, ?, 'analysis-request-v1', '{}'::jsonb, 'READY', now())",
                UUID.randomUUID(),
                runId));
    }

    @Test
    void databaseRejectsRunAndRequestMutationAndDelete() {
        UUID caseId = createDraftCase("INV-E11");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e11");
        UUID runId = (UUID) run(caseId, 1).get("id");
        UUID requestId = (UUID) request(caseId, 1).get("id");

        // Input identity/hash is immutable and the run is not deletable: the
        // raised guard (23000), not a check/fk/unique violation.
        assertDatabaseRejects("23000", () -> jdbc.update(
                "update analysis_run set evidence_payload_hash = 'tampered' where id = ?", runId));
        assertDatabaseRejects("23000", () -> jdbc.update(
                "update analysis_run set input_version = 9 where id = ?", runId));
        assertDatabaseRejects("23000", () -> jdbc.update(
                "delete from analysis_run where id = ?", runId));

        // Schema/payload is immutable and the request is not deletable.
        assertDatabaseRejects("23000", () -> jdbc.update(
                "update analysis_request_outbox set payload = '{}'::jsonb where id = ?", requestId));
        assertDatabaseRejects("23000", () -> jdbc.update(
                "update analysis_request_outbox set schema_version = 'x' where id = ?", requestId));
        assertDatabaseRejects("23000", () -> jdbc.update(
                "delete from analysis_request_outbox where id = ?", requestId));

        // Only QUEUED -> STALE and READY -> CANCELLED are legal: the raised
        // transition guard (23514), not a plain CHECK violation.
        jdbc.update("update analysis_run set status = 'STALE', updated_at = now() where id = ?", runId);
        assertDatabaseRejects("23514", () -> jdbc.update(
                "update analysis_run set status = 'QUEUED', updated_at = now() where id = ?", runId));
        jdbc.update("update analysis_request_outbox set status = 'CANCELLED' where id = ?", requestId);
        assertDatabaseRejects("23514", () -> jdbc.update(
                "update analysis_request_outbox set status = 'READY' where id = ?", requestId));
    }

    private String submissionOutcome(UUID caseId, String requestId, long version, CountDownLatch start)
            throws InterruptedException {
        TestActors.as("submitter", "SUBMITTER");
        try {
            start.await(30, TimeUnit.SECONDS);
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(caseId, requestId, version));
            return "OK";
        } catch (StaleCaseVersionException e) {
            return "STALE_CASE_VERSION";
        } finally {
            TestActors.clear();
        }
    }

    private void insertRun(UUID caseId, UUID bundleId, int inputVersion, String hash) {
        jdbc.update("insert into analysis_run"
                        + " (id, invoice_case_id, evidence_bundle_id, input_version, evidence_payload_hash,"
                        + " workflow_version, status, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, 'document-parser-v1', 'QUEUED', now(), now())",
                UUID.randomUUID(), caseId, bundleId, inputVersion, hash);
    }

    private static DocumentEvidence toEvidence(SeededDocument document) {
        return new DocumentEvidence(
                document.documentId(),
                document.sourceDraftRevisionId(),
                document.fileName(),
                document.mediaType(),
                document.sizeBytes(),
                document.checksum());
    }

    private static void assertDocumentMetadata(JsonNode node, SeededDocument document) {
        assertThat(node.get("documentId").asText()).isEqualTo(document.documentId().toString());
        assertThat(node.get("sourceDraftRevisionId").asText())
                .isEqualTo(document.sourceDraftRevisionId().toString());
        assertThat(node.get("fileName").asText()).isEqualTo(document.fileName());
        assertThat(node.get("mediaType").asText()).isEqualTo(document.mediaType());
        assertThat(node.get("sizeBytes").asLong()).isEqualTo(document.sizeBytes());
        assertThat(node.get("checksum").asText()).isEqualTo(document.checksum());
    }

    private JsonNode readJson(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
