package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.application.AnalysisInput;
import com.invoicematch.core.analysis.application.AnalysisRequestService;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.domain.StaleCaseVersionException;
import com.invoicematch.core.support.TestActors;
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
import org.springframework.dao.DataAccessException;
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
    PlatformTransactionManager transactionManager;

    @Test
    void enabledDocumentSubmissionReservesRunAndRequestWithCanonicalPayload() {
        UUID caseId = createDraftCase("INV-E1");
        UUID revision = currentDraftRevisionId(caseId);
        UUID documentId = seedCompletedDocument(caseId);

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

        JsonNode documents = payload.get("documents");
        assertThat(documents.size()).isEqualTo(1);
        JsonNode document = documents.get(0);
        assertThat(document.get("documentId").asText()).isEqualTo(documentId.toString());
        assertThat(document.get("sourceDraftRevisionId").asText()).isEqualTo(revision.toString());
        assertThat(document.get("fileName").asText()).isEqualTo("invoice.pdf");
        assertThat(document.get("mediaType").asText()).isEqualTo("application/pdf");
        assertThat(document.get("sizeBytes").asLong()).isEqualTo(4L);
        assertThat(document.get("checksum").asText()).matches("[0-9a-f]{64}");

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
        String bundleHash = (String) bundle(caseId, 1).get("payload_hash");
        UUID bundleId = (UUID) bundle(caseId, 1).get("id");

        // A newer submission whose frozen bundle carries no documents (legacy)
        // must still stale the lower reservation and cancel its request while
        // reserving nothing of its own.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                analysisRequests.onEvidenceSubmitted(
                        new AnalysisInput(caseId, bundleId, 2, bundleHash, List.of())));

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
        assertThatThrownBy(() -> insertRun(caseB, bundleA, 1, "h"))
                .isInstanceOf(DataAccessException.class);
        // Case A has no evidence bundle version 2: composite FK rejects.
        assertThatThrownBy(() -> insertRun(caseA, bundleA, 2, "h"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsDuplicateRunAndRequest() {
        UUID caseId = createDraftCase("INV-E10");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e10");
        UUID runId = (UUID) run(caseId, 1).get("id");
        UUID bundleId = (UUID) bundle(caseId, 1).get("id");

        assertThatThrownBy(() -> insertRun(caseId, bundleId, 1, "h"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into analysis_request_outbox"
                                + " (id, analysis_run_id, schema_version, payload, status, created_at)"
                                + " values (?, ?, 'analysis-request-v1', '{}'::jsonb, 'READY', now())",
                        UUID.randomUUID(),
                        runId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsRunAndRequestMutationAndDelete() {
        UUID caseId = createDraftCase("INV-E11");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-e11");
        UUID runId = (UUID) run(caseId, 1).get("id");
        UUID requestId = (UUID) request(caseId, 1).get("id");

        // Input identity/hash is immutable and the run is not deletable.
        assertThatThrownBy(() -> jdbc.update(
                        "update analysis_run set evidence_payload_hash = 'tampered' where id = ?", runId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update analysis_run set input_version = 9 where id = ?", runId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from analysis_run where id = ?", runId))
                .isInstanceOf(DataAccessException.class);

        // Schema/payload is immutable and the request is not deletable.
        assertThatThrownBy(() -> jdbc.update(
                        "update analysis_request_outbox set payload = '{}'::jsonb where id = ?", requestId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update analysis_request_outbox set schema_version = 'x' where id = ?", requestId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "delete from analysis_request_outbox where id = ?", requestId))
                .isInstanceOf(DataAccessException.class);

        // Only QUEUED -> STALE and READY -> CANCELLED are legal.
        jdbc.update("update analysis_run set status = 'STALE', updated_at = now() where id = ?", runId);
        assertThatThrownBy(() -> jdbc.update(
                        "update analysis_run set status = 'QUEUED', updated_at = now() where id = ?", runId))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("update analysis_request_outbox set status = 'CANCELLED' where id = ?", requestId);
        assertThatThrownBy(() -> jdbc.update(
                        "update analysis_request_outbox set status = 'READY' where id = ?", requestId))
                .isInstanceOf(DataAccessException.class);
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

    private JsonNode readJson(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
