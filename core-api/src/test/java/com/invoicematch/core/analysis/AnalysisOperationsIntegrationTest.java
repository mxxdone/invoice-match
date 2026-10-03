package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.support.TestActors;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AnalysisOperationsIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @Autowired AnalysisOperationsService operations;
    @Autowired AnalysisRecoveryService recovery;
    private AnalysisOperationsService.RetryCommand command(int attempt) {
        return new AnalysisOperationsService.RetryCommand(UUID.randomUUID().toString(),attempt,"원인 수정 확인");
    }
    private void dead(RunFixture f) {
        var c=claim(f);recovery.failure(f.runId(),new AnalysisFailureCommand(c.claimToken(),1,f.evidencePayloadHash(),"WORKER_FAILED"));
    }
    @Test void onlyOperatorCanReadAndRetryWithBoundedPaging() {
        var f=preparePdfRun(1);dead(f);
        for(String role:new String[]{"SUBMITTER","APPROVER"}) {
            assertThatThrownBy(()->TestActors.call("actor",role,()->operations.jobs(null,0,20))).isInstanceOf(com.invoicematch.core.security.ForbiddenActionException.class);
            assertThatThrownBy(()->TestActors.call("actor",role,()->operations.failures(f.runId(),0,20))).isInstanceOf(com.invoicematch.core.security.ForbiddenActionException.class);
            assertThatThrownBy(()->TestActors.call("actor",role,()->operations.retry(f.runId(),command(1)))).isInstanceOf(com.invoicematch.core.security.ForbiddenActionException.class);
        }
        TestActors.run("operator","OPERATOR",()->{
            var page=operations.jobs("DEAD_LETTERED",0,20);assertThat(page.totalElements()).isEqualTo(1);
            assertThat(page.items().get(0).lastErrorCode()).isEqualTo("WORKER_FAILED");
            assertThat(operations.failures(f.runId(),0,20).items()).hasSize(1);
            assertThatThrownBy(()->operations.jobs("BAD",0,20)).isInstanceOf(AnalysisValidationException.class);
            assertThatThrownBy(()->operations.jobs(null,0,101)).isInstanceOf(AnalysisValidationException.class);
        });
    }
    @Test void retryPreservesPartialResultsAndOriginalEventAndReplaysAfterCompletion() {
        var f=preparePdfRun(2);var c=claim(f);var a=f.documents().get(0);var b=f.documents().get(1);
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),a.documentId(),"SUCCESS",pdfResult(a,"partial"),null));
        var before=jdbc.queryForMap("select * from analysis_document_result where run_id=?",f.runId());
        recovery.failure(f.runId(),new AnalysisFailureCommand(c.claimToken(),1,f.evidencePayloadHash(),"WORKER_FAILED"));
        var request=request(f.caseId(),1);var cmd=command(1);
        var reserved=TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),cmd));
        assertThat(reserved.body().attemptLimit()).isEqualTo(4);
        var next=claim(f);execution.recordResult(f.runId(),resultCommand(f,next.claimToken(),b.documentId(),"SUCCESS",pdfResult(b,"done"),null));
        assertThat(TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),cmd))).isEqualTo(reserved);
        assertThat(jdbc.queryForMap("select * from analysis_document_result where run_id=? and document_id=?",f.runId(),a.documentId())).isEqualTo(before);
        assertThat(request(f.caseId(),1)).isEqualTo(request);
        assertThat(TestActors.call("operator","OPERATOR",()->operations.jobs("COMPLETED",0,20)).items().get(0).nextRetryAt()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action='ANALYSIS_RETRY_RESERVED'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),
            new AnalysisOperationsService.RetryCommand(cmd.requestId(),1,"다른 사유")))).isInstanceOf(com.invoicematch.core.invoicecase.application.IdempotencyConflictException.class);
        assertThatThrownBy(()->TestActors.call("other","OPERATOR",()->operations.retry(f.runId(),cmd))).isInstanceOf(AnalysisConflictException.class);
    }
    @Test void auditFailureRollsBackReservationAndSameRequestCanRetry() {
        var f=preparePdfRun(1);dead(f);var cmd=command(1);
        jdbc.execute("alter table audit_entry add constraint test_operator_audit check(action<>'ANALYSIS_RETRY_RESERVED') not valid");
        try { assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),cmd))).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table audit_entry drop constraint test_operator_audit"); }
        assertThat(run(f.caseId(),1).get("status")).isEqualTo("DEAD_LETTERED");
        assertThat(jdbc.queryForObject("select count(*) from analysis_recovery_dispatch where dedup_key like 'operator:%'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='analysis:retry'",Integer.class)).isZero();
        assertThat(TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),cmd)).body().status()).isEqualTo("QUEUED");
    }
    @Test void concurrentIdenticalRequestsReserveExactlyOnce() throws Exception {
        var f=preparePdfRun(1);dead(f);var cmd=command(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),cmd)));
            var b=pool.submit(()->TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),cmd)));
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("select count(*) from analysis_recovery_dispatch where dedup_key like 'operator:%'",Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void parserFailureExposesOnlyFixedCodeAndRequiresSupplement() {
        var f=preparePdfRun(1);var c=claim(f);
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),f.documents().get(0).documentId(),"FAILURE",null,"PDF_CORRUPT"));
        var rows=TestActors.call("operator","OPERATOR",()->operations.jobs("FAILED",0,20)).items();
        assertThat(rows).hasSize(1);assertThat(rows.get(0).lastErrorCode()).isEqualTo("PDF_CORRUPT");
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),command(1)))).isInstanceOf(AnalysisConflictException.class);
    }
    @Test void activeAndWrongExpectedAttemptAreRejectedWithoutEffects() {
        var f=preparePdfRun(1);
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),command(1)))).isInstanceOf(AnalysisConflictException.class);
        dead(f);
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->operations.retry(f.runId(),command(2)))).isInstanceOf(AnalysisConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='analysis:retry'",Integer.class)).isZero();
    }
}
