package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.invoicematch.core.analysis.application.AnalysisClaimCommand;
import com.invoicematch.core.analysis.application.AnalysisConflictException;
import com.invoicematch.core.document.persistence.DocumentStore;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.persistence.DraftRevisionRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceLineRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AnalysisManifestVerifierIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @Autowired EvidenceBundlePayloadHasher hasher;
    @Autowired DraftRevisionRepository revisions;
    @Autowired InvoiceLineRepository lines;
    @Autowired DocumentStore documents;

    @Test
    void validSubmittedManifestCanBeClaimed() {
        assertThat(claim(preparePdfRun(2)).documents()).hasSize(2);
    }

    @Test
    void forgedFrozenEvidenceIsRejectedBeforeAnyExecutionMutation() throws Exception {
        for (String defect : new String[] {"valid", "schema", "checksum", "sourceRevision", "duplicate",
                "missingDocument", "empty", "storedHash", "runHash", "case", "revision"}) {
            RunFixture fixture = preparePdfRun(2);
            UUID revisionId = fixture.documents().get(0).sourceDraftRevisionId();
            int revisionNumber = revisions.findById(revisionId).orElseThrow().revisionNumber();
            String canonical = hasher.canonicalize(invoiceCases.findById(fixture.caseId()).orElseThrow(),
                    revisionNumber, lines.findByDraftRevisionIdOrderByLineNumberAsc(revisionId),
                    documents.evidenceForRevision(revisionId)).json();
            ObjectNode payload = (ObjectNode) json.readTree(canonical);
            ArrayNode frozen = (ArrayNode) payload.get("documents");
            switch (defect) {
                case "schema" -> payload.put("schemaVersion", 999);
                case "checksum" -> ((ObjectNode) frozen.get(0)).put("checksum", "0".repeat(64));
                case "sourceRevision" -> ((ObjectNode) frozen.get(0))
                        .put("sourceDraftRevisionId", UUID.randomUUID().toString());
                case "duplicate" -> frozen.add(frozen.get(0).deepCopy());
                case "missingDocument" -> frozen.remove(0);
                case "empty" -> frozen.removeAll();
                case "case" -> payload.put("caseId", UUID.randomUUID().toString());
                case "revision" -> payload.put("revisionNumber", revisionNumber + 1);
                default -> { }
            }
            String stored = payload.toString();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            if (defect.equals("storedHash")) hash = "f".repeat(64);
            String runHash = defect.equals("runHash") ? "e".repeat(64) : hash;
            UUID bundleId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                    + " payload_schema, payload_hash, payload, submitted_at)"
                    + " values (?, ?, ?, 2, 'document-v2', ?, cast(? as jsonb), now())",
                    bundleId, fixture.caseId(), revisionId, hash, stored);
            jdbc.update("insert into analysis_run (id, invoice_case_id, evidence_bundle_id, input_version,"
                    + " evidence_payload_hash, workflow_version, status, created_at, updated_at)"
                    + " values (?, ?, ?, 2, ?, ?, 'QUEUED', now(), now())",
                    runId, fixture.caseId(), bundleId, runHash, fixture.workflowVersion());
            jdbc.update("insert into analysis_request_outbox"
                    + " (id, analysis_run_id, schema_version, payload, status, created_at)"
                    + " values (?, ?, 'analysis-request-v1', '{}'::jsonb, 'READY', now())", eventId, runId);
            Map<String, Object> before = jdbc.queryForMap("select * from analysis_run where id = ?", runId);
            if (defect.equals("valid")) {
                assertThat(execution.claim(runId, new AnalysisClaimCommand(
                        eventId, 2, runHash, fixture.workflowVersion())))
                        .isInstanceOf(com.invoicematch.core.analysis.application.ClaimOutcome.Claimed.class);
                continue;
            }
            assertThatThrownBy(() -> execution.claim(runId, new AnalysisClaimCommand(
                    eventId, 2, runHash, fixture.workflowVersion())))
                    .as(defect).isInstanceOfSatisfying(AnalysisConflictException.class,
                            error -> assertThat(error.code()).isEqualTo("MANIFEST_MISMATCH"));
            assertThat(jdbc.queryForMap("select * from analysis_run where id = ?", runId))
                    .as(defect).isEqualTo(before);
            assertThat(count("analysis_document_result")).isZero();
        }
    }
}
