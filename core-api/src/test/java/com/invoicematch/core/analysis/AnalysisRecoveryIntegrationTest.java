package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.analysis.application.AnalysisConflictException;
import com.invoicematch.core.analysis.application.AnalysisFailureCommand;
import com.invoicematch.core.analysis.application.AnalysisRecoveryService;
import com.invoicematch.core.analysis.application.ClaimOutcome;
import com.invoicematch.core.analysis.application.ResultOutcome;
import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import com.invoicematch.core.analysis.persistence.AnalysisRecoveryStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AnalysisRecoveryIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @Autowired AnalysisRecoveryService recovery;
    @Autowired AnalysisRecoveryStore recoveryStore;
    @Autowired PlatformTransactionManager transactionManager;
    @DynamicPropertySource static void lease(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("analysis.execution.lease-duration", () -> "5s");
    }
    private AnalysisFailureCommand failure(RunFixture f, UUID token, String code) {
        return new AnalysisFailureCommand(token,f.inputVersion(),f.evidencePayloadHash(),code);
    }
    private int count(String table,UUID run) {
        return jdbc.queryForObject("select count(*) from "+table+" where "+(table.equals("analysis_execution_failure") ? "run_id" : "analysis_run_id")+"=?",Integer.class,run);
    }
    private void due(UUID run) throws InterruptedException {
        long end=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(System.nanoTime()<end) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("select bool_and(next_attempt_at<=clock_timestamp())"
                    + " from analysis_recovery_dispatch where analysis_run_id=? and destination='REQUEST'",Boolean.class,run))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Retry never became due");
    }

    @Test void checkpointReplaysAndUsesDatabaseBackoffWithoutChangingInitialEvent() {
        var f=preparePdfRun(1); var c=claim(f);
        var before=request(f.caseId(),1);
        var saved=recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_TRANSIENT"));
        assertThat(saved.runStatus()).isEqualTo("RETRY_SCHEDULED");
        assertThat(recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_TRANSIENT"))).isEqualTo(saved);
        assertThat(count("analysis_execution_failure",f.runId())).isEqualTo(1);
        assertThat(count("analysis_recovery_dispatch",f.runId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select extract(epoch from next_attempt_at-created_at) from analysis_recovery_dispatch where analysis_run_id=?",
                Double.class,f.runId())).isBetween(5.0,7.6);
        assertThat(execution.claim(f.runId(),claimCommand(f))).isInstanceOf(ClaimOutcome.Busy.class);
        assertDatabaseRejects("23514",()->jdbc.update("update analysis_run set status='RUNNING',execution_token=gen_random_uuid(),"
                + "lease_until=clock_timestamp()+interval '1 minute',execution_attempt=execution_attempt+1 where id=?",f.runId()));
        assertThat(request(f.caseId(),1)).isEqualTo(before);
    }

    @Test void threeTransientAttemptsExhaustToOneDurableDlqDispatch() throws Exception {
        var f=preparePdfRun(1);
        for(int i=1;i<=3;i++) {
            var c=claim(f);
            var outcome=recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_UNAVAILABLE"));
            assertThat(outcome.runStatus()).isEqualTo(i<3 ? "RETRY_SCHEDULED" : "DEAD_LETTERED");
            if(i<3) due(f.runId());
        }
        assertThat(count("analysis_execution_failure",f.runId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from analysis_recovery_dispatch where analysis_run_id=? and destination='DLQ'",Integer.class,f.runId())).isEqualTo(1);
        assertThat(execution.claim(f.runId(),claimCommand(f))).isInstanceOf(ClaimOutcome.AlreadyFinished.class);
        assertThat(jdbc.queryForObject("select execution_attempt from analysis_run where id=?",Integer.class,f.runId())).isEqualTo(3);
    }

    @Test void partialResultSurvivesRetryAndReplaysWithoutRewrite() throws Exception {
        var f=preparePdfRun(2); var first=claim(f); var a=f.documents().get(0); var b=f.documents().get(1);
        execution.recordResult(f.runId(),resultCommand(f,first.claimToken(),a.documentId(),"SUCCESS",pdfResult(a,"first"),null));
        var before=jdbc.queryForMap("select * from analysis_document_result where run_id=? and document_id=?",f.runId(),a.documentId());
        recovery.failure(f.runId(),failure(f,first.claimToken(),"CORE_UNAVAILABLE")); due(f.runId());
        var second=claim(f);
        assertThat(execution.recordResult(f.runId(),resultCommand(f,second.claimToken(),a.documentId(),"SUCCESS",pdfResult(a,"first"),null)))
                .isEqualTo(new ResultOutcome.Replayed(AnalysisRunStatus.RUNNING));
        assertThat(execution.recordResult(f.runId(),resultCommand(f,second.claimToken(),b.documentId(),"SUCCESS",pdfResult(b,"second"),null)))
                .isEqualTo(new ResultOutcome.Accepted(AnalysisRunStatus.COMPLETED));
        assertThat(jdbc.queryForMap("select * from analysis_document_result where run_id=? and document_id=?",f.runId(),a.documentId())).isEqualTo(before);
    }

    @Test void busyCheckpointWaitsForCurrentLeaseAndDoesNotStealOrDuplicate() {
        var f=preparePdfRun(1); var c=claim(f);
        recovery.defer(f.runId(),claimCommand(f)); recovery.defer(f.runId(),claimCommand(f));
        assertThat(count("analysis_recovery_dispatch",f.runId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select o.next_attempt_at>=r.lease_until from analysis_recovery_dispatch o join analysis_run r on r.id=o.analysis_run_id where r.id=?",Boolean.class,f.runId())).isTrue();
        assertThat(jdbc.queryForObject("select execution_token from analysis_run where id=?",UUID.class,f.runId())).isEqualTo(c.claimToken());
        assertThatThrownBy(()->recovery.failure(f.runId(),failure(f,UUID.randomUUID(),"CORE_TRANSIENT"))).isInstanceOf(AnalysisConflictException.class);
    }

    @Test void checkpointAndStateRollBackTogether() {
        var f=preparePdfRun(1);var c=claim(f);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_TRANSIENT"));tx.setRollbackOnly();
        });
        assertThat(run(f.caseId(),1).get("status")).isEqualTo("RUNNING");
        assertThat(count("analysis_execution_failure",f.runId())).isZero();
        assertThat(count("analysis_recovery_dispatch",f.runId())).isZero();
    }

    @Test void terminalResultResponseLossDoesNotScheduleAnotherExecution() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().get(0);
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"done"),null));
        assertThat(recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_UNAVAILABLE")).runStatus()).isEqualTo("COMPLETED");
        assertThat(count("analysis_execution_failure",f.runId())).isZero();
    }

    @Test void permanentFailureDoesNotRetryAndImmutableHistoryIsGuarded() {
        var f=preparePdfRun(1);var c=claim(f);
        recovery.failure(f.runId(),failure(f,c.claimToken(),"SOURCE_MISMATCH"));
        assertThat(run(f.caseId(),1).get("status")).isEqualTo("DEAD_LETTERED");
        assertDatabaseRejects("23000",()->jdbc.update("delete from analysis_execution_failure where run_id=?",f.runId()));
        assertDatabaseRejects("23000",()->jdbc.update("update analysis_recovery_dispatch set payload='{}'::jsonb where analysis_run_id=?",f.runId()));
        assertDatabaseRejects("23514",()->jdbc.update("update analysis_run set attempt_limit=100 where id=?",f.runId()));
    }

    @Test void staleRunCancelsRecoveryWithoutDroppingFailureHistory() {
        var f=preparePdfRun(1);var c=claim(f);
        recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_TRANSIENT"));
        jdbc.update("update analysis_run set status='STALE' where id=?",f.runId());
        recoveryStore.recover();
        assertThat(jdbc.queryForObject("select status from analysis_recovery_dispatch where analysis_run_id=?",String.class,f.runId())).isEqualTo("CANCELLED");
        assertThat(count("analysis_execution_failure",f.runId())).isEqualTo(1);
        assertThat(recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_TRANSIENT")).runStatus()).isEqualTo("STALE");
    }

    @Test void anotherRunsEventCannotBeAttachedToRecoveryDispatch() {
        var first=preparePdfRun(1);var second=preparePdfRun(1);
        assertDatabaseRejects("23503",()->jdbc.update("insert into analysis_recovery_dispatch(id,analysis_run_id,event_id,dedup_key,destination,schema_version,payload,next_attempt_at)"
                + " values (?,?,?,'forged','REQUEST','analysis-request-v1','{}'::jsonb,clock_timestamp())",
                UUID.randomUUID(),first.runId(),second.eventId()));
    }

    @Test void anOlderBusyDeferralDoesNotBlockTheCurrentFailureBackoff() throws Exception {
        var f=preparePdfRun(1);var c=claim(f);
        jdbc.update("update analysis_run set lease_until=clock_timestamp()+interval '1 minute' where id=?",f.runId());
        recovery.defer(f.runId(),claimCommand(f));
        recovery.failure(f.runId(),failure(f,c.claimToken(),"CORE_TRANSIENT"));
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end) {
            var outcome=execution.claim(f.runId(),claimCommand(f));
            if(outcome instanceof ClaimOutcome.Claimed) {
                assertThat(jdbc.queryForObject("select execution_attempt from analysis_run where id=?",Integer.class,f.runId())).isEqualTo(2);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Old deferral delayed the current retry");
    }

    @Test void recoveryPublicationLeaseReclaimsAndFencesTheOldPublisher() throws Exception {
        var f=preparePdfRun(1);var c=claim(f);
        recovery.failure(f.runId(),failure(f,c.claimToken(),"SOURCE_MISMATCH"));
        assertDatabaseRejects("23514",()->jdbc.update("update analysis_recovery_dispatch set status='CLAIMED',claim_token=gen_random_uuid(),"
                + "lease_until=clock_timestamp()+interval '1 minute',attempt_count=attempt_count+2 where analysis_run_id=?",f.runId()));
        var first=recoveryStore.claim(Duration.ofSeconds(1)).orElseThrow();
        assertThat(first.eventId()).isEqualTo(f.eventId());
        Thread.sleep(1100);
        assertDatabaseRejects("23514",()->jdbc.update("update analysis_recovery_dispatch set status='PUBLISHED',claim_token=null,lease_until=null,"
                + "published_at=clock_timestamp() where id=?",first.id()));
        recoveryStore.recover();
        var second=recoveryStore.claim(Duration.ofSeconds(5)).orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(recoveryStore.settle(first,true,null,Duration.ZERO)).isFalse();
        assertThat(recoveryStore.settle(second,true,null,Duration.ZERO)).isTrue();
        assertDatabaseRejects("23514",()->jdbc.update("update analysis_recovery_dispatch set next_attempt_at=clock_timestamp()+interval '1 minute' where id=?",second.id()));
        assertDatabaseRejects("23000",()->jdbc.update("delete from analysis_recovery_dispatch where id=?",second.id()));
    }
}
