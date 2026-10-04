package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class ProposalIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @DynamicPropertySource static void aiProperties(DynamicPropertyRegistry r) { r.add("analysis.ai.enabled",()->true); }
    @Autowired ProposalService proposals;
    @Autowired ProposalExecutionService ai;
    private RunFixture ready() {
        var f=preparePdfRun(1);var claim=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,claim.claimToken(),d.documentId(),"SUCCESS",
                pdfResult(d,"Premium Copy Paper A4 60 2500"),null));
        match(f);return f;
    }
    private void match(RunFixture f) { TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString()))); }
    private ProposalService.ReserveCommand command(RunFixture f) { return new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())); }
    private ProposalService.Reserved reserve(RunFixture f) { return TestActors.call("operator","OPERATOR",()->proposals.reserve(f.caseId(),command(f))).body(); }

    @Test void reservationBindsVerifiedContextWithoutBusinessEffectsAndReplays() throws Exception {
        var f=ready();long version=caseVersion(f.caseId());var cmd=command(f);
        var before=jdbc.queryForList("select * from analysis_document_result");
        var result=TestActors.call("operator","OPERATOR",()->proposals.reserve(f.caseId(),cmd));
        assertThat(TestActors.call("operator","OPERATOR",()->proposals.reserve(f.caseId(),cmd))).isEqualTo(result);
        assertThat(reserve(f).id()).isEqualTo(result.body().id());
        var stored=jdbc.queryForMap("select * from proposal_run");
        var context=json.readTree(stored.get("context").toString());
        assertThat(context.path("documents").get(0).path("parsed").path("pdf").path("pages").get(0).path("text").asText())
                .isEqualTo("Premium Copy Paper A4 60 2500");
        assertThat(context.path("matchResultId").asText()).isEqualTo(stored.get("match_result_id").toString());
        assertThat(context.path("items").size()).isPositive();
        assertThat(context.toString()).doesNotContain("objectKey","uploadKey",WORKER_TOKEN,"submittedBy");
        assertThat(count("proposal_request_outbox")).isEqualTo(1);
        assertThat(count("proposal_run")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action='AI_ANALYSIS_RESERVED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from analysis_document_result")).isEqualTo(before);
        assertThat(caseVersion(f.caseId())).isEqualTo(version);
        assertThat(count("receipt_allocation")).isZero();assertThat(count("payment_request")).isZero();
    }
    @Test void unauthorizedOrIncompleteInputsLeaveNoReservation() {
        var f=preparePdfRun(1);
        assertThatThrownBy(()->TestActors.call("submitter","SUBMITTER",()->proposals.reserve(f.caseId(),command(f))))
                .isInstanceOf(com.invoicematch.core.security.ForbiddenActionException.class);
        assertThatThrownBy(()->reserve(f)).isInstanceOf(AnalysisConflictException.class);
        var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"ok"),null));
        assertThatThrownBy(()->reserve(f)).isInstanceOf(AnalysisConflictException.class);
        assertThat(count("proposal_run")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='proposal:reserve'",Integer.class)).isZero();
    }
    @Test void auditRollbackThenConcurrentReplayReservesOneJob() throws Exception {
        var f=ready();var cmd=command(f);
        jdbc.execute("alter table audit_entry add constraint reject_ai_audit check(action<>'AI_ANALYSIS_RESERVED') not valid");
        try { assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->proposals.reserve(f.caseId(),cmd))).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("alter table audit_entry drop constraint reject_ai_audit"); }
        assertThat(count("proposal_run")).isZero();assertThat(count("proposal_request_outbox")).isZero();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->TestActors.call("operator","OPERATOR",()->proposals.reserve(f.caseId(),cmd)));
            var b=pool.submit(()->TestActors.call("operator","OPERATOR",()->proposals.reserve(f.caseId(),cmd)));
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        } finally { pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
        assertThat(count("proposal_run")).isEqualTo(1);assertThat(count("proposal_request_outbox")).isEqualTo(1);
    }
    @Test void claimsAndBudgetReservationsAreFencedAndNeverReset() {
        var f=ready();var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());
        assertThat(c.disposition()).isEqualTo("CLAIMED");assertThat(ai.claim(r.id(),r.contextHash()).disposition()).isEqualTo("BUSY");
        assertThatThrownBy(()->ai.heartbeat(r.id(),r.contextHash(),UUID.randomUUID())).isInstanceOf(AnalysisConflictException.class);
        UUID request=UUID.randomUUID();assertThat(ai.reserveCall(r.id(),r.contextHash(),c.token(),request,8000)).isTrue();
        assertThat(ai.reserveCall(r.id(),r.contextHash(),c.token(),request,8000)).isFalse();
        assertThatThrownBy(()->ai.reserveCall(r.id(),r.contextHash(),c.token(),request,8001)).isInstanceOf(AnalysisConflictException.class);
        for(int i=0;i<4;i++) ai.reserveCall(r.id(),r.contextHash(),c.token(),UUID.randomUUID(),8000);
        assertThatThrownBy(()->ai.reserveCall(r.id(),r.contextHash(),c.token(),UUID.randomUUID(),1)).isInstanceOf(AnalysisConflictException.class);
        assertThat(count("proposal_call_reservation")).isEqualTo(5);
        assertDatabaseRejects("23514",()->jdbc.update("update proposal_run set reserved_calls=0 where id=?",r.id()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from proposal_call_reservation where run_id=?",r.id()));
        assertThatThrownBy(()->ai.heartbeat(r.id(),"0".repeat(64),c.token())).isInstanceOf(AnalysisConflictException.class);
    }
    @Test void actualLeaseExpiryRejectsOldTokenAndReclaimsSameInput() throws Exception {
        var f=ready();var r=reserve(f);UUID abandoned=UUID.randomUUID();
        jdbc.update("update proposal_run set status='RUNNING',execution_token=?,execution_attempt=1,"
                + " lease_until=clock_timestamp()+interval '1 second' where id=?",abandoned,r.id());
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(Boolean.TRUE.equals(jdbc.queryForObject("select lease_until>clock_timestamp() from proposal_run where id=?",Boolean.class,r.id()))) {
            assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(50);
        }
        assertThatThrownBy(()->ai.reserveCall(r.id(),r.contextHash(),abandoned,UUID.randomUUID(),100)).isInstanceOf(AnalysisConflictException.class);
        assertDatabaseRejects("23514",()->jdbc.update("update proposal_run set lease_until=clock_timestamp()+interval '2 minutes' where id=?",r.id()));
        var reclaimed=ai.claim(r.id(),r.contextHash());assertThat(reclaimed.token()).isNotEqualTo(abandoned);
        assertThat(jdbc.queryForObject("select execution_attempt from proposal_run where id=?",Integer.class,r.id())).isEqualTo(2);
        assertThatThrownBy(()->ai.heartbeat(r.id(),r.contextHash(),abandoned)).isInstanceOf(AnalysisConflictException.class);
    }
    @Test void newMatchStalesOldInputAndCannotUseItAsCurrent() {
        var f=ready();var old=reserve(f);var c=ai.claim(old.id(),old.contextHash());match(f);
        assertThatThrownBy(()->ai.heartbeat(old.id(),old.contextHash(),c.token())).isInstanceOf(AnalysisConflictException.class);
        assertThat(ai.claim(old.id(),old.contextHash()).disposition()).isEqualTo("STALE");
        var next=reserve(f);assertThat(next.id()).isNotEqualTo(old.id());
        assertThat(jdbc.queryForObject("select status from proposal_run where id=?",String.class,old.id())).isEqualTo("STALE");
        assertThat(count("analysis_document_result")).isEqualTo(1);
    }
    @Test void checkpointIsImmutableReplayableAndRejectsSourceForgeryWithoutEffects() {
        var f=ready();var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());
        ai.reserveCall(r.id(),r.contextHash(),c.token(),UUID.randomUUID(),1000);
        var output=json.createObjectNode().put("schemaVersion","invoice-extraction-v1").put("promptVersion","invoice-advisory-1");
        output.putArray("calls").addObject().put("model","fixture-v1").put("inputTokens",100).put("outputTokens",100).put("latencyMs",1);
        var result=output.putObject("result");var field=result.putArray("fields").addObject().put("name","supplierName")
                .put("value","Premium Copy Paper A4");
        field.putObject("source").put("segmentId",f.documents().getFirst().documentId()+":page:1").put("start",0).put("end",21);
        result.putArray("lines");result.putArray("warnings");
        assertThat(ai.checkpoint(r.id(),r.contextHash(),c.token(),"document",output)).isEqualTo("ACCEPTED");
        assertThat(ai.checkpoint(r.id(),r.contextHash(),UUID.randomUUID(),"document",output)).isEqualTo("REPLAYED");
        var before=jdbc.queryForList("select * from proposal_step");
        field.put("value","forged");
        assertThatThrownBy(()->ai.checkpoint(r.id(),r.contextHash(),c.token(),"document",output)).isInstanceOf(AnalysisValidationException.class);
        assertThat(jdbc.queryForList("select * from proposal_step")).isEqualTo(before);
        match(f);field.put("value","Premium Copy Paper A4");
        assertThat(ai.checkpoint(r.id(),r.contextHash(),c.token(),"document",output)).isEqualTo("STALE");
        assertThat(jdbc.queryForList("select * from proposal_step")).isEqualTo(before);
    }
    @Test void databaseRejectsInputMutationCrossCaseAndImmutableResultChanges() {
        var f=ready();var r=reserve(f);var other=ready();
        assertDatabaseRejects("23000",()->jdbc.update("update proposal_run set context_hash=? where id=?","0".repeat(64),r.id()));
        assertDatabaseRejects("23000",()->jdbc.update("delete from proposal_run where id=?",r.id()));
        assertDatabaseRejects("23503",()->jdbc.update("insert into proposal_run(id,invoice_case_id,evidence_bundle_id,parser_run_id,"
                + " match_result_id,context_hash,context,workflow_version,status) select ?,?,evidence_bundle_id,?,"
                + " match_result_id,context_hash,context,workflow_version,status from proposal_run where id=?",UUID.randomUUID(),other.caseId(),other.runId(),r.id()));
        jdbc.update("insert into proposal_step(run_id,stage,payload,payload_hash) values(?,'document','{}',?)",r.id(),"1".repeat(64));
        assertDatabaseRejects("P0001",()->jdbc.update("update proposal_step set payload='{}' where run_id=?",r.id()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from proposal_step where run_id=?",r.id()));
    }
}
