package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.invoicecase.application.*;
import com.invoicematch.core.review.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class GraphSuccessorIntegrationTest extends AbstractGraphReviewIntegrationTest {
    @Autowired PolicyCatalogService policies;
    @Autowired com.invoicematch.core.purchasingreference.application.PurchasingReferenceService purchasing;
    @Autowired com.invoicematch.core.approval.application.ApprovalApplicationService approvals;

    private ReviewSnapshotView freeze(Fixture f) {
        return TestActors.call("approver","APPROVER",()->reviewService.freezeSnapshot(
            new FreezeReviewSnapshotCommand(f.parser().caseId(),UUID.randomUUID().toString(),caseVersion(f.parser().caseId())))).body();
    }
    private RecordMappingDecisionCommand mapping(Fixture f,ReviewSnapshotView snapshot,String request) {
        return new RecordMappingDecisionCommand(f.parser().caseId(),request,caseVersion(f.parser().caseId()),snapshot.id(),snapshot.payloadHash(),1,ITEM_A);
    }
    private GraphExecutionService.Reserved successor(Fixture f,String request) {
        return TestActors.call("operator","OPERATOR",()->graph.successor(f.parser().caseId(),f.graph().id(),
            new ProposalService.ReserveCommand(request,caseVersion(f.parser().caseId())))).body();
    }
    private void stale(Fixture f) {
        var row=jdbc.queryForMap("select status,execution_token,lease_until from graph_run where id=?",f.graph().id());
        assertThat(row.get("status")).isEqualTo("STALE");assertThat(row.get("execution_token")).isNull();assertThat(row.get("lease_until")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from graph_dispatch d join graph_event e on e.id=d.event_id where e.run_id=? and d.status in ('READY','CLAIMED')",Integer.class,f.graph().id())).isZero();
    }
    private void noAutomaticBusinessEffects() {
        assertThat(jdbc.queryForObject("select count(*) from graph_proposal",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from receipt_allocation",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_request",Integer.class)).isZero();
    }
    @Test void actualMappingCancelsPendingResumeAndSuccessorStartsFreshWithIdempotentProvenance() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));var snapshot=freeze(f);
        var event=json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));
        var oldBudget=jdbc.queryForMap("select reserved_calls,reserved_tokens,reserved_cost,checkpoint_count,write_count from graph_run where id=?",f.graph().id());
        var map=mapping(f,snapshot,"map");TestActors.run("approver","APPROVER",()->reviewService.recordMapping(map));stale(f);
        assertThat(delivery.claimResume(f.graph().id(),f.graph().contextHash(),event).disposition()).isEqualTo("ALREADY_FINISHED");
        assertThat(jdbc.queryForObject("select count(*) from graph_resume_consumption",Integer.class)).isZero();
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->graph.reserve(f.parser().caseId(),new ProposalService.ReserveCommand("implicit",caseVersion(f.parser().caseId())))))
            .isInstanceOf(AnalysisConflictException.class).extracting(e->((AnalysisConflictException)e).code()).isEqualTo("GRAPH_SUCCESSOR_REQUIRED");
        var fresh=successor(f,"fresh");assertThat(successor(f,"fresh")).isEqualTo(fresh);assertThat(successor(f,"other-request")).isEqualTo(fresh);
        assertThat(fresh.id()).isNotEqualTo(f.graph().id());assertThat(fresh.contextHash()).isNotEqualTo(f.graph().contextHash());
        assertThat(store.predecessor(fresh.id())).contains(f.graph().id());
        var next=jdbc.queryForMap("select reserved_calls,reserved_tokens,reserved_cost,checkpoint_count,write_count from graph_run where id=?",fresh.id());
        for(var value:next.values())assertThat(new java.math.BigDecimal(value.toString())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForMap("select reserved_calls,reserved_tokens,reserved_cost,checkpoint_count,write_count from graph_run where id=?",f.graph().id())).isEqualTo(oldBudget);
        assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_successor"));
        noAutomaticBusinessEffects();
    }
    @Test void mappingAuditFailureRollsBackGraphCancellationAndBusinessMutationTogether() {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));var snapshot=freeze(f);var map=mapping(f,snapshot,"map-retry");
        var before=jdbc.queryForMap("select * from graph_run where id=?",f.graph().id());long version=caseVersion(f.parser().caseId());
        jdbc.execute("alter table audit_entry add constraint reject_graph_mapping_audit check(action<>'ITEM_MAPPED') not valid");
        try {assertThatThrownBy(()->TestActors.run("approver","APPROVER",()->reviewService.recordMapping(map))).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("alter table audit_entry drop constraint reject_graph_mapping_audit");}
        assertThat(jdbc.queryForMap("select * from graph_run where id=?",f.graph().id())).isEqualTo(before);assertThat(caseVersion(f.parser().caseId())).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from graph_dispatch where status='READY'",Integer.class)).isEqualTo(2);
        TestActors.run("approver","APPROVER",()->reviewService.recordMapping(map));stale(f);noAutomaticBusinessEffects();
    }
    @Test void supplementSubmissionRejectsPendingAndFailedParserThenRequiresLatestMatch() {
        var f=waiting(-1);var snapshot=freeze(f);UUID caseId=f.parser().caseId();
        TestActors.run("approver","APPROVER",()->reviewService.requestSupplement(new RequestSupplementCommand(caseId,"supplement",caseVersion(caseId),snapshot.id(),snapshot.payloadHash(),"원문 보완")));
        stale(f);
        TestActors.run("submitter","SUBMITTER",()->invoiceCaseCommands.openSupplementRevision(new OpenSupplementRevisionCommand(caseId,"open",caseVersion(caseId))));
        submit(caseId,"supplement-submit");
        assertThatThrownBy(()->successor(f,"pending")).isInstanceOf(AnalysisConflictException.class);
        var row=run(caseId,2);UUID parser=(UUID)row.get("id"),event=(UUID)request(caseId,2).get("id");
        var claim=(ClaimOutcome.Claimed)execution.claim(parser,new AnalysisClaimCommand(event,2,(String)row.get("evidence_payload_hash"),"document-parser-v1"));
        var d=f.parser().documents().getFirst();
        execution.recordResult(parser,new AnalysisDocumentResultCommand(claim.claimToken(),2,(String)row.get("evidence_payload_hash"),d.documentId(),"FAILURE",null,"PDF_CORRUPT"));
        assertThatThrownBy(()->successor(f,"failed")).isInstanceOf(AnalysisConflictException.class);noAutomaticBusinessEffects();
    }
    @Test void unchangedInputCrossCaseAndUnauthorizedSuccessorsHaveZeroEffects() throws Exception {
        var f=waiting(-1);
        assertThatThrownBy(()->successor(f,"unchanged")).isInstanceOf(AnalysisConflictException.class).extracting(e->((AnalysisConflictException)e).code()).isEqualTo("GRAPH_SUCCESSOR_INPUT_UNCHANGED");
        var other=waiting(-1);
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->graph.successor(other.parser().caseId(),f.graph().id(),new ProposalService.ReserveCommand("cross",caseVersion(other.parser().caseId())))))
            .isInstanceOf(AnalysisRunNotFoundException.class);
        String path="/api/invoice-cases/"+f.parser().caseId()+"/graphs/"+f.graph().id()+"/successors";
        String body=json.writeValueAsString(new ProposalService.ReserveCommand("forbidden",caseVersion(f.parser().caseId())));
        mvc.perform(post(path).with(httpBasic("approver","approver-pass")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from graph_successor",Integer.class)).isZero();noAutomaticBusinessEffects();
    }
    @Test void successfulSupplementParserStillRequiresNewMatchBeforeFreshSuccessor() {
        var f=waiting(-1);advanceToSupplementDraft(f.parser().caseId(),"new-document");
        var added=seedDocument(f.parser().caseId(),UUID.randomUUID(),"corrected.pdf","application/pdf",12,"c".repeat(64));
        submit(f.parser().caseId(),"second-submit");
        var row=run(f.parser().caseId(),2);
        var next=new RunFixture(f.parser().caseId(),(UUID)row.get("id"),(UUID)request(f.parser().caseId(),2).get("id"),2,
            (String)row.get("evidence_payload_hash"),"document-parser-v1",List.of(f.parser().documents().getFirst(),added));
        var owned=claim(next);
        for(var doc:next.documents())execution.recordResult(next.runId(),resultCommand(next,owned.claimToken(),doc.documentId(),"SUCCESS",pdfResult(doc,"corrected paper 60 2500"),null));
        assertThatThrownBy(()->successor(f,"before-match")).isInstanceOf(AnalysisConflictException.class);
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(next.caseId(),"new-match")));
        var fresh=successor(f,"new-input");
        assertThat(graph.claim(fresh.id(),fresh.contextHash()).context().path("parserRunId").asText()).isEqualTo(next.runId().toString());
        assertThat(jdbc.queryForObject("select count(*) from graph_stage where run_id=?",Integer.class,fresh.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from graph_review where run_id=?",Integer.class,fresh.id())).isZero();
        stale(f);noAutomaticBusinessEffects();
    }
    @Test void approvalAndResumeRaceOnlyHumanApprovalCreatesPaymentAndFencesOldThread() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));var snapshot=freeze(f);
        var event=json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));
        var approval=new com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand(f.parser().caseId(),"approve-race",caseVersion(f.parser().caseId()),snapshot.id(),snapshot.payloadHash());
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{gate.await();return delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);});
            var b=pool.submit(()->{gate.await();return TestActors.call("approver","APPROVER",()->approvals.approve(approval));});gate.countDown();
            var claim=a.get(20,TimeUnit.SECONDS);assertThat(b.get(20,TimeUnit.SECONDS).body().status()).isEqualTo("EXPORT_PENDING");stale(f);
            if(claim.token()!=null)assertThatThrownBy(()->stages.complete(f.graph().id(),f.graph().contextHash(),claim.token())).isInstanceOf(AnalysisConflictException.class);
        } finally {gate.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        assertThat(jdbc.queryForObject("select count(*) from graph_proposal",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_request",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->successor(f,"after-approval")).isInstanceOf(AnalysisConflictException.class);
    }
    @Test void concurrentSuccessorReservationsConvergeAndResumeMappingRaceCannotCompleteOldThread() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));var snapshot=freeze(f);var map=mapping(f,snapshot,"map-race");
        var event=json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{gate.await();return delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);});
            var b=pool.submit(()->{gate.await();TestActors.run("approver","APPROVER",()->reviewService.recordMapping(map));return true;});gate.countDown();
            var claimed=a.get(20,TimeUnit.SECONDS);assertThat(b.get(20,TimeUnit.SECONDS)).isTrue();stale(f);
            if(claimed.token()!=null)assertThatThrownBy(()->stages.complete(f.graph().id(),f.graph().contextHash(),claimed.token())).isInstanceOf(AnalysisConflictException.class);
            var c=pool.submit(()->successor(f,"first"));var d=pool.submit(()->successor(f,"second"));assertThat(c.get(20,TimeUnit.SECONDS)).isEqualTo(d.get(20,TimeUnit.SECONDS));
        } finally {gate.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        assertThat(jdbc.queryForObject("select count(*) from graph_successor",Integer.class)).isEqualTo(1);noAutomaticBusinessEffects();
    }
    @Test void policyPublicationOnAnotherCaseBlocksUntilResumeOwnerReleasesScopeAndThenInvalidates() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));var other=preparePdfRun(1);
        var event=json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));
        var owned=delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var reader=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{delivery.resume(f.graph().id(),f.graph().contextHash(),owned.token(),event);entered.countDown();try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var publication=pool.submit(()->TestActors.call("operator","OPERATOR",()->policies.publish(other.caseId(),new PolicyCatalogService.Publish("policy",caseVersion(other.caseId()),"CONTRACT-1","graph-policy",1,"검토 기준",LocalDate.of(2020,1,1),LocalDate.of(2030,1,1),"manual-fixture","1",List.of(new PolicyCatalogService.ChunkInput(1,1,"사람 확인 필요",new float[]{1,0}))))));
            assertThatThrownBy(()->publication.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();assertThat(reader.get(15,TimeUnit.SECONDS)).isTrue();publication.get(15,TimeUnit.SECONDS);
            assertThat(delivery.defer(f.graph().id(),f.graph().contextHash(),"RESUME",event).runStatus()).isEqualTo("STALE");stale(f);
            assertThatThrownBy(()->stages.complete(f.graph().id(),f.graph().contextHash(),owned.token())).isInstanceOf(AnalysisConflictException.class);noAutomaticBusinessEffects();
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void purchasingRefreshWaitsForValidatedResumeThenOldInputCannotContinue() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));
        var event=json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));
        var owned=delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        purchasingResponse(com.invoicematch.core.support.PurchasingPayloads.confirmedPartialReceipt().snapshotVersion(6).toJson());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var reader=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{delivery.resume(f.graph().id(),f.graph().contextHash(),owned.token(),event);entered.countDown();try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var refresh=pool.submit(()->purchasing.refresh(new com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand(
                com.invoicematch.core.shared.domain.PurchaseOrderId.of(PO_ID),com.invoicematch.core.shared.domain.SupplierId.of(SUPPLIER))));
            assertThatThrownBy(()->refresh.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();assertThat(reader.get(15,TimeUnit.SECONDS)).isTrue();refresh.get(15,TimeUnit.SECONDS);
            assertThat(delivery.defer(f.graph().id(),f.graph().contextHash(),"RESUME",event).runStatus()).isEqualTo("STALE");stale(f);
            assertThatThrownBy(()->successor(f,"old-match")).isInstanceOf(AnalysisConflictException.class);noAutomaticBusinessEffects();
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void policyOutsideFrozenApplicableDateKeepsWaitingAndApplicableChangeAllowsExplicitSuccessor() {
        var f=waiting(-1);UUID caseId=f.parser().caseId();
        TestActors.run("operator","OPERATOR",()->policies.publish(caseId,new PolicyCatalogService.Publish("future",caseVersion(caseId),"CONTRACT-1","future",1,"향후 기준",LocalDate.of(2035,1,1),LocalDate.of(2040,1,1),"manual-fixture","1",List.of(new PolicyCatalogService.ChunkInput(1,1,"향후 적용",new float[]{1,0})))));
        assertThat(jdbc.queryForObject("select status from graph_run where id=?",String.class,f.graph().id())).isEqualTo("WAITING_HUMAN");
        TestActors.run("operator","OPERATOR",()->policies.publish(caseId,new PolicyCatalogService.Publish("current",caseVersion(caseId),"CONTRACT-1","current",1,"현재 기준",LocalDate.of(2020,1,1),LocalDate.of(2030,1,1),"manual-fixture","1",List.of(new PolicyCatalogService.ChunkInput(1,1,"현재 적용",new float[]{1,0})))));
        stale(f);assertThat(successor(f,"new-policy").id()).isNotEqualTo(f.graph().id());noAutomaticBusinessEffects();
    }
}
