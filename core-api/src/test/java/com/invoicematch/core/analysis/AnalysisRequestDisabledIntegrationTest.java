package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P2-05 fail-closed behavior with the default {@code analysis.request.enabled=
 * false} against real PostgreSQL: even a document-bearing submission reserves
 * nothing, yet a newer submission still stales a lower reservation and cancels
 * its request, and that cancellation rolls back with a failed submission.
 */
class AnalysisRequestDisabledIntegrationTest extends AbstractAnalysisIntegrationTest {

    @Test
    void disabledDocumentSubmissionReservesNothing() {
        UUID caseId = createDraftCase("INV-D1");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-d1");

        assertThat(count("analysis_run")).isZero();
        assertThat(count("analysis_request_outbox")).isZero();
    }

    @Test
    void disabledSupplementStillStalesAndCancelsLowerReservationAndRollsBackOnAuditFailure() {
        UUID caseId = createDraftCase("INV-D2");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-d2-v1");
        // A reservation that was created while analysis was enabled earlier.
        seedReservation(caseId, 1);

        advanceToSupplementDraft(caseId, "d2");
        seedCompletedDocument(caseId);

        jdbc.execute("alter table audit_entry add constraint test_analysis_disabled_audit_failure"
                + " check (action <> 'CASE_SUBMITTED') not valid");
        try {
            assertThatThrownBy(() -> submit(caseId, "submit-d2-v2")).isInstanceOf(RuntimeException.class);
            assertThat(count("analysis_run")).isEqualTo(1);
            assertThat(count("analysis_request_outbox")).isEqualTo(1);
            assertThat(run(caseId, 1).get("status")).isEqualTo("QUEUED");
            assertThat(request(caseId, 1).get("status")).isEqualTo("READY");
        } finally {
            jdbc.execute("alter table audit_entry drop constraint test_analysis_disabled_audit_failure");
        }

        submit(caseId, "submit-d2-v2");
        // Cancellation happened even though analysis is disabled and no new
        // reservation was created.
        assertThat(count("analysis_run")).isEqualTo(1);
        assertThat(count("analysis_request_outbox")).isEqualTo(1);
        assertThat(run(caseId, 1).get("status")).isEqualTo("STALE");
        assertThat(request(caseId, 1).get("status")).isEqualTo("CANCELLED");
    }

    private void seedReservation(UUID caseId, int versionNumber) {
        Map<String, Object> bundle = bundle(caseId, versionNumber);
        UUID runId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("insert into analysis_run"
                        + " (id, invoice_case_id, evidence_bundle_id, input_version, evidence_payload_hash,"
                        + " workflow_version, status, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, 'document-parser-v1', 'QUEUED', now(), now())",
                runId, caseId, bundle.get("id"), versionNumber, bundle.get("payload_hash"));
        jdbc.update("insert into analysis_request_outbox"
                        + " (id, analysis_run_id, schema_version, payload, status, created_at)"
                        + " values (?, ?, 'analysis-request-v1', cast(? as jsonb), 'READY', now())",
                eventId, runId, "{\"analysisRunId\":\"" + runId + "\"}");
    }
}
