package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.analysis.application.AnalysisConflictException;
import com.invoicematch.core.analysis.application.AnalysisDocumentResultCommand;
import com.invoicematch.core.analysis.application.ClaimOutcome;
import com.invoicematch.core.analysis.application.HeartbeatOutcome;
import com.invoicematch.core.analysis.application.ResultOutcome;
import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * P2-07 analysis execution against real PostgreSQL: the claim/lease/heartbeat
 * state machine, per-document idempotent result reflection, terminal decisions,
 * reclaim replay, stale suppression, concurrency and same-transaction rollback.
 */
class AnalysisExecutionLifecycleIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {

    @Test
    void claimReturnsAuthoritativeManifestAndRunningLease() {
        RunFixture fixture = preparePdfRun(2);
        ClaimOutcome.Claimed claimed = claim(fixture);

        assertThat(claimed.claimToken()).isNotNull();
        assertThat(claimed.invoiceCaseId()).isEqualTo(fixture.caseId());
        assertThat(claimed.documents()).hasSize(2);
        assertThat(claimed.documents().stream().map(document -> document.documentId()))
                .containsExactlyInAnyOrder(
                        fixture.documents().get(0).documentId(), fixture.documents().get(1).documentId());

        assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                        "select execution_attempt from analysis_run where id = ?", Integer.class, fixture.runId()))
                .isEqualTo(1);
    }

    @Test
    void activeClaimIsBusyForASecondClaim() {
        RunFixture fixture = preparePdfRun(1);
        claim(fixture);

        ClaimOutcome second = execution.claim(fixture.runId(), claimCommand(fixture));

        assertThat(second).isInstanceOf(ClaimOutcome.Busy.class);
        assertThat(((ClaimOutcome.Busy) second).leaseUntil()).isNotNull();
    }

    @Test
    void expiredClaimIsReclaimedWithNewTokenAndOldTokenIsRejected() {
        RunFixture fixture = preparePdfRun(1);
        ClaimOutcome.Claimed first = claim(fixture);
        UUID oldToken = first.claimToken();
        expireLease(fixture.runId());

        ClaimOutcome.Claimed second = claim(fixture);

        assertThat(second.claimToken()).isNotEqualTo(oldToken);
        assertThat(jdbc.queryForObject(
                        "select execution_attempt from analysis_run where id = ?", Integer.class, fixture.runId()))
                .isEqualTo(2);
        assertThatThrownBy(() -> execution.heartbeat(
                        fixture.runId(), heartbeatCommand(fixture, oldToken)))
                .isInstanceOf(AnalysisConflictException.class);
    }

    @Test
    void heartbeatExtendsTheLeaseAndRejectsAStaleToken() {
        RunFixture fixture = preparePdfRun(1);
        ClaimOutcome.Claimed claimed = claim(fixture);

        HeartbeatOutcome heartbeat = execution.heartbeat(
                fixture.runId(), heartbeatCommand(fixture, claimed.claimToken()));
        assertThat(heartbeat.leaseUntil()).isNotNull();

        assertThatThrownBy(() -> execution.heartbeat(
                        fixture.runId(), heartbeatCommand(fixture, UUID.randomUUID())))
                .isInstanceOf(AnalysisConflictException.class);
    }

    @Test
    void lastDocumentCompletesRunAndReplaysIdempotently() {
        RunFixture fixture = preparePdfRun(2);
        ClaimOutcome.Claimed claimed = claim(fixture);
        SeededDocument first = fixture.documents().get(0);
        SeededDocument second = fixture.documents().get(1);

        ResultOutcome firstOutcome = execution.recordResult(
                fixture.runId(),
                resultCommand(fixture, claimed.claimToken(), first.documentId(), "SUCCESS",
                        pdfResult(first, "page one"), null));
        assertThat(firstOutcome).isEqualTo(new ResultOutcome.Accepted(AnalysisRunStatus.RUNNING));

        ResultOutcome lastOutcome = execution.recordResult(
                fixture.runId(),
                resultCommand(fixture, claimed.claimToken(), second.documentId(), "SUCCESS",
                        pdfResult(second, "page two"), null));
        assertThat(lastOutcome).isEqualTo(new ResultOutcome.Accepted(AnalysisRunStatus.COMPLETED));
        assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("COMPLETED");

        // The same canonical result is replayed even after the terminal state.
        ResultOutcome replay = execution.recordResult(
                fixture.runId(),
                resultCommand(fixture, UUID.randomUUID(), first.documentId(), "SUCCESS",
                        pdfResult(first, "page one"), null));
        assertThat(replay).isEqualTo(new ResultOutcome.Replayed(AnalysisRunStatus.COMPLETED));

        assertThatThrownBy(() -> execution.recordResult(
                        fixture.runId(),
                        resultCommand(fixture, claimed.claimToken(), first.documentId(), "SUCCESS",
                                pdfResult(first, "a different page"), null)))
                .isInstanceOf(AnalysisConflictException.class);
    }

    @Test
    void oneFailureAmongDocumentsFinalizesFailed() {
        RunFixture fixture = preparePdfRun(2);
        ClaimOutcome.Claimed claimed = claim(fixture);
        SeededDocument first = fixture.documents().get(0);
        SeededDocument second = fixture.documents().get(1);

        execution.recordResult(fixture.runId(), resultCommand(
                fixture, claimed.claimToken(), first.documentId(), "SUCCESS", pdfResult(first, "text"), null));
        ResultOutcome outcome = execution.recordResult(fixture.runId(), resultCommand(
                fixture, claimed.claimToken(), second.documentId(), "FAILURE", null, "PDF_CORRUPT"));

        assertThat(outcome).isEqualTo(new ResultOutcome.Accepted(AnalysisRunStatus.FAILED));
        assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("FAILED");
    }

    @Test
    void partialResultSurvivesReclaimAndIsReused() {
        RunFixture fixture = preparePdfRun(2);
        ClaimOutcome.Claimed claimed = claim(fixture);
        SeededDocument first = fixture.documents().get(0);
        SeededDocument second = fixture.documents().get(1);

        execution.recordResult(fixture.runId(), resultCommand(
                fixture, claimed.claimToken(), first.documentId(), "SUCCESS", pdfResult(first, "one"), null));
        expireLease(fixture.runId());

        ClaimOutcome.Claimed reclaimed = claim(fixture);
        assertThat(reclaimed.claimToken()).isNotEqualTo(claimed.claimToken());
        assertThat(count("analysis_document_result")).isEqualTo(1);

        ResultOutcome replayFirst = execution.recordResult(fixture.runId(), resultCommand(
                fixture, reclaimed.claimToken(), first.documentId(), "SUCCESS", pdfResult(first, "one"), null));
        assertThat(replayFirst).isEqualTo(new ResultOutcome.Replayed(AnalysisRunStatus.RUNNING));

        ResultOutcome last = execution.recordResult(fixture.runId(), resultCommand(
                fixture, reclaimed.claimToken(), second.documentId(), "SUCCESS", pdfResult(second, "two"), null));
        assertThat(last).isEqualTo(new ResultOutcome.Accepted(AnalysisRunStatus.COMPLETED));
        assertThat(count("analysis_document_result")).isEqualTo(2);
    }

    @Test
    void unknownManifestDocumentIsRejectedWithoutInsert() {
        RunFixture fixture = preparePdfRun(1);
        ClaimOutcome.Claimed claimed = claim(fixture);

        assertThatThrownBy(() -> execution.recordResult(fixture.runId(), resultCommand(
                        fixture, claimed.claimToken(), UUID.randomUUID(), "SUCCESS", null, null)))
                .isInstanceOf(AnalysisConflictException.class);
        assertThat(count("analysis_document_result")).isZero();
    }

    @Test
    void xlsxResultIsAccepted() {
        UUID caseId = createDraftCase("INV-XLSX-" + UUID.randomUUID());
        SeededDocument document = seedDocument(caseId, UUID.randomUUID(), "book.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 20, "d".repeat(64));
        submit(caseId, "submit-" + caseId);
        RunFixture fixture = fixture(caseId, List.of(document));
        ClaimOutcome.Claimed claimed = claim(fixture);

        ResultOutcome outcome = execution.recordResult(fixture.runId(), resultCommand(
                fixture, claimed.claimToken(), document.documentId(), "SUCCESS",
                AnalysisResultFixtures.xlsx(document.documentId(), document.sizeBytes(), document.checksum(), "KRW"),
                null));

        assertThat(outcome).isEqualTo(new ResultOutcome.Accepted(AnalysisRunStatus.COMPLETED));
    }

    @Test
    void newResultRequiresTheActiveClaim() {
        RunFixture fixture = preparePdfRun(1);
        claim(fixture);

        assertThatThrownBy(() -> execution.recordResult(fixture.runId(), resultCommand(
                        fixture, UUID.randomUUID(), fixture.documents().get(0).documentId(), "SUCCESS",
                        pdfResult(fixture.documents().get(0), "text"), null)))
                .isInstanceOf(AnalysisConflictException.class);
        assertThat(count("analysis_document_result")).isZero();
    }

    @Test
    void supersedingSubmissionStalesRunningRunAndSuppressesItsResults() {
        RunFixture fixture = preparePdfRun(1);
        ClaimOutcome.Claimed claimed = claim(fixture);

        advanceToSupplementDraft(fixture.caseId(), "stale");
        seedDocument(fixture.caseId(), UUID.randomUUID(), "v2.pdf", "application/pdf", 12, "e".repeat(64));
        submit(fixture.caseId(), "submit-v2-" + fixture.caseId());

        assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("STALE");
        assertThat(jdbc.queryForObject(
                        "select execution_token from analysis_run where id = ?", UUID.class, fixture.runId()))
                .isNull();

        assertThat(execution.claim(fixture.runId(), claimCommand(fixture)))
                .isEqualTo(new ClaimOutcome.Stale());
        assertThat(execution.recordResult(fixture.runId(), resultCommand(
                        fixture, claimed.claimToken(), fixture.documents().get(0).documentId(), "SUCCESS",
                        pdfResult(fixture.documents().get(0), "text"), null)))
                .isEqualTo(new ResultOutcome.Stale());
        assertThat(count("analysis_document_result")).isZero();
    }

    @Test
    void auditFailureRollsBackTheStaleOfARunningRunThenRetryStalesIt() {
        RunFixture fixture = preparePdfRun(1);
        claim(fixture);
        advanceToSupplementDraft(fixture.caseId(), "audit");
        seedDocument(fixture.caseId(), UUID.randomUUID(), "v2.pdf", "application/pdf", 12, "f".repeat(64));

        jdbc.execute("alter table audit_entry add constraint test_p2_07_audit_failure"
                + " check (action <> 'CASE_SUBMITTED') not valid");
        try {
            assertThatThrownBy(() -> submit(fixture.caseId(), "submit-audit-" + fixture.caseId()))
                    .isInstanceOf(RuntimeException.class);
            // The stale of the running run rolled back with the failed submission.
            assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject(
                            "select execution_token from analysis_run where id = ?", UUID.class, fixture.runId()))
                    .isNotNull();
        } finally {
            jdbc.execute("alter table audit_entry drop constraint test_p2_07_audit_failure");
        }

        submit(fixture.caseId(), "submit-audit-" + fixture.caseId());
        assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("STALE");
    }

    @Test
    void terminalTransitionFailureRollsBackTheInsert() {
        RunFixture fixture = preparePdfRun(1);
        ClaimOutcome.Claimed claimed = claim(fixture);
        SeededDocument document = fixture.documents().get(0);

        jdbc.execute("alter table analysis_run add constraint test_p2_07_no_complete"
                + " check (status <> 'COMPLETED') not valid");
        try {
            assertThatThrownBy(() -> execution.recordResult(fixture.runId(), resultCommand(
                            fixture, claimed.claimToken(), document.documentId(), "SUCCESS",
                            pdfResult(document, "text"), null)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("analysis_document_result")).isZero();
            assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("RUNNING");
        } finally {
            jdbc.execute("alter table analysis_run drop constraint test_p2_07_no_complete");
        }

        ResultOutcome retry = execution.recordResult(fixture.runId(), resultCommand(
                fixture, claimed.claimToken(), document.documentId(), "SUCCESS", pdfResult(document, "text"), null));
        assertThat(retry).isEqualTo(new ResultOutcome.Accepted(AnalysisRunStatus.COMPLETED));
        assertThat(count("analysis_document_result")).isEqualTo(1);
    }

    @Test
    void concurrentClaimsProduceExactlyOneClaimed() throws Exception {
        RunFixture fixture = preparePdfRun(1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ClaimOutcome> call = () -> {
                start.await(30, TimeUnit.SECONDS);
                return execution.claim(fixture.runId(), claimCommand(fixture));
            };
            Future<ClaimOutcome> a = pool.submit(call);
            Future<ClaimOutcome> b = pool.submit(call);
            start.countDown();
            List<ClaimOutcome> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(ClaimOutcome.Claimed.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ClaimOutcome.Busy.class::isInstance).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                        "select execution_attempt from analysis_run where id = ?", Integer.class, fixture.runId()))
                .isEqualTo(1);
    }

    private void expireLease(UUID runId) {
        jdbc.update("update analysis_run set lease_until = clock_timestamp() - interval '1 second' where id = ?",
                runId);
    }
}
