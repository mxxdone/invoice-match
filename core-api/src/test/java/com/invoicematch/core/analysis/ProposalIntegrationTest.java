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
    @Autowired ProposalToolService tools;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired PolicyCatalogService policies;
    @Autowired PolicySearchService search;
    @Autowired com.invoicematch.core.analysis.persistence.PolicyCatalogStore policyStore;
    @org.junit.jupiter.api.BeforeEach void resetPolicyCatalog() { jdbc.execute("truncate table policy_contract cascade"); }
    @Autowired com.invoicematch.core.approval.application.ApprovalApplicationService approval;
    private RunFixture ready() { return ready("Premium Copy Paper A4 60 2500"); }
    private RunFixture ready(String sourceText) {
        var f=preparePdfRun(1);var claim=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,claim.claimToken(),d.documentId(),"SUCCESS",
                pdfResult(d,sourceText),null));
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
    @Test void toolsUseFrozenScopeReplayOnceAndCannotWriteOrCrossScope() {
        var f=ready();var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());long version=caseVersion(f.caseId());
        UUID request=UUID.randomUUID();var args=new ProposalToolService.Request(request,"get_purchase_order","",10);
        var output=tools.call(r.id(),r.contextHash(),c.token(),args);
        var context=jsonValue(c.context());
        assertThat(output.path("result").path("purchaseOrderId")).isEqualTo(context.path("matchResult").path("purchaseOrderId"));
        assertThat(output.path("result").path("items").get(0).path("orderedQuantity").asInt()).isPositive();
        assertThat(tools.call(r.id(),r.contextHash(),c.token(),args)).isEqualTo(output);
        assertThat(jdbc.queryForObject("select tool_calls from proposal_run where id=?",Integer.class,r.id())).isEqualTo(1);
        assertThatThrownBy(()->tools.call(r.id(),r.contextHash(),c.token(),
                new ProposalToolService.Request(request,"search_items","",10))).isInstanceOf(AnalysisConflictException.class);
        assertThatThrownBy(()->tools.call(r.id(),r.contextHash(),c.token(),
                new ProposalToolService.Request(UUID.randomUUID(),"approve_payment","",1))).isInstanceOf(AnalysisValidationException.class);
        var candidates=tools.call(r.id(),r.contextHash(),c.token(),new ProposalToolService.Request(UUID.randomUUID(),"search_items","FOREIGN-SUPPLIER",1));
        assertThat(candidates.path("result").size()).isZero();
        var receipts=tools.call(r.id(),r.contextHash(),c.token(),new ProposalToolService.Request(UUID.randomUUID(),"get_receipts","",10));
        assertThat(receipts.path("result")).isEqualTo(context.path("matchResult").path("purchasingSnapshot").path("receipts"));
        assertThat(output.toString()).doesNotContain("objectKey","decidedBy","username",WORKER_TOKEN);
        assertThat(caseVersion(f.caseId())).isEqualTo(version);assertThat(count("payment_request")).isZero();
        match(f);
        assertThatThrownBy(()->tools.call(r.id(),r.contextHash(),c.token(),args)).isInstanceOf(AnalysisConflictException.class);
    }
    @Test void distinctToolCallsSpendBoundedBudgetAndRejectStaleOwners() {
        var f=ready();var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());
        assertThatThrownBy(()->tools.call(r.id(),r.contextHash(),UUID.randomUUID(),
                new ProposalToolService.Request(UUID.randomUUID(),"search_items","",1))).isInstanceOf(AnalysisConflictException.class);
        assertThatThrownBy(()->tools.call(r.id(),r.contextHash(),c.token(),
                new ProposalToolService.Request(UUID.randomUUID(),"search_items","x".repeat(101),1))).isInstanceOf(AnalysisValidationException.class);
        for(int i=0;i<8;i++) tools.call(r.id(),r.contextHash(),c.token(),new ProposalToolService.Request(UUID.randomUUID(),"get_prior_invoice_cases","",1));
        assertThatThrownBy(()->tools.call(r.id(),r.contextHash(),c.token(),
                new ProposalToolService.Request(UUID.randomUUID(),"search_items","",1))).isInstanceOf(AnalysisConflictException.class);
        assertThat(jdbc.queryForObject("select tool_calls from proposal_run where id=?",Integer.class,r.id())).isEqualTo(8);
        assertThat(count("proposal_step")).isEqualTo(8);
    }
    @Test void priorMappingsRequireAnApprovedSnapshotAndReplayFrozenHistory() {
        var prior=ready();
        TestActors.run("approver","APPROVER",()->reviewService.freezeSnapshot(new com.invoicematch.core.review.application.FreezeReviewSnapshotCommand(
                prior.caseId(),UUID.randomUUID().toString(),caseVersion(prior.caseId()))));
        var snapshot=reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(prior.caseId()).orElseThrow();
        TestActors.run("approver","APPROVER",()->reviewService.recordMapping(new com.invoicematch.core.review.application.RecordMappingDecisionCommand(
                prior.caseId(),UUID.randomUUID().toString(),caseVersion(prior.caseId()),snapshot.id(),snapshot.payloadHash(),1,ITEM_A)));
        var target=ready();var r=reserve(target);var c=ai.claim(r.id(),r.contextHash());
        var before=tools.call(r.id(),r.contextHash(),c.token(),new ProposalToolService.Request(UUID.randomUUID(),"get_prior_invoice_cases","",10));
        assertThat(before.path("result").size()).isZero();
        var approvedSnapshot=reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(prior.caseId()).orElseThrow();
        TestActors.run("approver","APPROVER",()->approval.approve(new com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand(
                prior.caseId(),UUID.randomUUID().toString(),caseVersion(prior.caseId()),approvedSnapshot.id(),approvedSnapshot.payloadHash())));
        var args=new ProposalToolService.Request(UUID.randomUUID(),"get_prior_invoice_cases","",10);
        var after=tools.call(r.id(),r.contextHash(),c.token(),args);
        assertThat(after.path("result").size()).isEqualTo(1);
        assertThat(after.path("result").get(0).path("snapshotId").asText()).isEqualTo(approvedSnapshot.id().toString());
        assertThat(after.path("result").get(0).path("itemId").asText()).isEqualTo(ITEM_A);
        assertThat(after.toString()).doesNotContain("approver","decidedBy","invoiceNumber");
        assertThat(tools.call(r.id(),r.contextHash(),c.token(),args)).isEqualTo(after);
    }
    private PolicyCatalogService.Publish policy(RunFixture f,String key,int version,java.time.LocalDate from,
            java.time.LocalDate to,String text,float[] vector) {
        return new PolicyCatalogService.Publish(UUID.randomUUID().toString(),caseVersion(f.caseId()),"CONTRACT-1",key,version,
                "매입 처리 지침",from,to,"manual-fixture","1",java.util.List.of(new PolicyCatalogService.ChunkInput(1,1,text,vector)));
    }
    private PolicyCatalogService.Published publish(RunFixture f,PolicyCatalogService.Publish input) {
        return TestActors.call("operator","OPERATOR",()->policies.publish(f.caseId(),input)).body();
    }
    private PolicySearchService.Request query(String mode,String model,String revision,float[] vector) {
        return new PolicySearchService.Request(UUID.randomUUID(),"분할",mode,model,revision,vector,3);
    }
    @Test void policyCatalogIsAuthorizedImmutableVersionedAndScopedBeforeRanking() {
        var f=ready();var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var input=policy(f,"guide",1,date.minusDays(1),date.plusDays(1),"분할 납품 허용",new float[]{1,0});
        assertThatThrownBy(()->TestActors.call("submitter","SUBMITTER",()->policies.publish(f.caseId(),input)))
                .isInstanceOf(com.invoicematch.core.security.ForbiddenActionException.class);
        var old=publish(f,input);
        assertThat(TestActors.call("operator","OPERATOR",()->policies.publish(f.caseId(),input)).body()).isEqualTo(old);
        var latest=publish(f,policy(f,"guide",2,date.minusDays(1),date.plusDays(1),"분할 청구는 검수 완료분만 허용",new float[]{1,0}));
        publish(f,policy(f,"future",1,date.plusDays(1),date.plusDays(2),"분할 FUTURE",new float[]{1,0}));
        publish(f,policy(f,"expired",1,date.minusDays(3),date.minusDays(2),"분할 EXPIRED",new float[]{1,0}));
        // Adversarial catalog rows with other company/supplier/PO are valid rows, never this case's scope.
        policyStore.contract("foreign-company",SUPPLIER,"FOREIGN-COMPANY",PO_ID);
        var foreign=UUID.randomUUID();policyStore.document(foreign,"foreign-company",SUPPLIER,"FOREIGN-COMPANY","guide",1,
                "foreign",date.minusDays(1),date.plusDays(1),"0".repeat(64),"manual-fixture","1",2);
        policyStore.chunk(new com.invoicematch.core.analysis.persistence.PolicyCatalogStore.Chunk(UUID.randomUUID(),foreign,1,1,
                "분할 FOREIGN","1".repeat(64)),new float[]{1,0});
        policyStore.contract("company-demo","OTHER-SUPPLIER","OTHER-SUPPLIER",PO_ID);
        var otherSupplier=UUID.randomUUID();policyStore.document(otherSupplier,"company-demo","OTHER-SUPPLIER","OTHER-SUPPLIER","guide",1,
                "foreign",date.minusDays(1),date.plusDays(1),"0".repeat(64),"manual-fixture","1",2);
        policyStore.chunk(new com.invoicematch.core.analysis.persistence.PolicyCatalogStore.Chunk(UUID.randomUUID(),otherSupplier,1,1,
                "분할 OTHER SUPPLIER","1".repeat(64)),new float[]{1,0});
        jdbc.update("insert into purchase_order_snapshot select 'PO-OTHER',supplier_id,supplier_name,status,snapshot_version,"
                + "purchase_order_version,payload_hash,payload,retrieved_at,updated_at from purchase_order_snapshot where purchase_order_id=?",PO_ID);
        policyStore.contract("company-demo",SUPPLIER,"OTHER-PO","PO-OTHER");
        var otherPo=UUID.randomUUID();policyStore.document(otherPo,"company-demo",SUPPLIER,"OTHER-PO","guide",1,
                "foreign",date.minusDays(1),date.plusDays(1),"0".repeat(64),"manual-fixture","1",2);
        policyStore.chunk(new com.invoicematch.core.analysis.persistence.PolicyCatalogStore.Chunk(UUID.randomUUID(),otherPo,1,1,
                "분할 OTHER PO","1".repeat(64)),new float[]{1,0});
        // A newer restricted version cannot make an obsolete readable version eligible.
        publish(f,policy(f,"restricted",1,date.minusDays(1),date.plusDays(1),"분할 OLD PUBLIC",new float[]{1,0}));
        jdbc.update("insert into policy_document(id,company_id,supplier_id,contract_id,document_key,version,title,valid_from,valid_to,"
                + "payload_hash,embedding_model,embedding_version,embedding_dimension,read_scope) values(?,'company-demo',?,"
                + "'CONTRACT-1','restricted',2,'restricted',?,?,?,'manual-fixture','1',2,'RESTRICTED')",
                UUID.randomUUID(),SUPPLIER,date.minusDays(1),date.plusDays(1),"0".repeat(64));
        var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());
        var context=jsonValue(c.context());assertThat(context.path("policyDocuments").size()).isEqualTo(1);
        assertThat(context.path("policyDocuments").get(0).path("id").asText()).isEqualTo(latest.documentId().toString());
        var result=search.search(r.id(),r.contextHash(),c.token(),query("LEXICAL",null,null,null));
        assertThat(result.path("result").size()).isEqualTo(1);
        assertThat(result.path("result").get(0).path("documentVersion").asInt()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("FUTURE","EXPIRED","FOREIGN","OTHER SUPPLIER","OTHER PO","OLD PUBLIC");
        var metadata=tools.call(r.id(),r.contextHash(),c.token(),new ProposalToolService.Request(UUID.randomUUID(),"get_contract_metadata","",10));
        assertThat(metadata.path("result")).isEqualTo(context.path("policyDocuments"));
        if(policyStore.vectorAvailable()) for(String mode:java.util.List.of("VECTOR","HYBRID")) {
            var ranked=search.search(r.id(),r.contextHash(),c.token(),query(mode,"manual-fixture","1",new float[]{1,0}));
            assertThat(ranked.path("result").size()).isEqualTo(1);
            assertThat(ranked.path("result").get(0).path("documentId").asText()).isEqualTo(latest.documentId().toString());
        }
        assertDatabaseRejects("P0001",()->jdbc.update("update policy_document set title='changed' where id=?",latest.documentId()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from policy_chunk where document_id=?",latest.documentId()));
        assertDatabaseRejects("23514",()->policyStore.chunk(new com.invoicematch.core.analysis.persistence.PolicyCatalogStore.Chunk(
                UUID.randomUUID(),latest.documentId(),2,1,"invalid","0".repeat(64)),new float[]{1}));
        publish(f,policy(f,"guide",3,date.minusDays(1),date.plusDays(1),"분할 새 지침",new float[]{1,0}));
        assertThatThrownBy(()->search.search(r.id(),r.contextHash(),c.token(),query("LEXICAL",null,null,null))).isInstanceOf(AnalysisConflictException.class);
        assertThat(ai.claim(r.id(),r.contextHash()).disposition()).isEqualTo("STALE");
    }
    @Test void exactVectorAndHybridAreRealOrFailClosedWithoutTheExtension() {
        var f=ready();var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var a=publish(f,policy(f,"a",1,date.minusDays(1),date.plusDays(1),"분할 청구 허용",new float[]{1,0}));
        publish(f,policy(f,"b",1,date.minusDays(1),date.plusDays(1),"배송 주소 변경",new float[]{0,1}));
        var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());var request=query("VECTOR","manual-fixture","1",new float[]{1,0});
        assertThatThrownBy(()->search.search(r.id(),r.contextHash(),c.token(),query("VECTOR","other","1",new float[]{1,0})))
                .isInstanceOf(AnalysisValidationException.class);
        assertThatThrownBy(()->search.search(r.id(),r.contextHash(),c.token(),query("VECTOR","manual-fixture","2",new float[]{1,0})))
                .isInstanceOf(AnalysisValidationException.class);
        assertThatThrownBy(()->search.search(r.id(),r.contextHash(),c.token(),query("VECTOR","manual-fixture","1",new float[]{0,0})))
                .isInstanceOf(AnalysisValidationException.class);
        if(!policyStore.vectorAvailable()) {
            assertThatThrownBy(()->search.search(r.id(),r.contextHash(),c.token(),request)).isInstanceOf(AnalysisValidationException.class)
                    .extracting(e->((AnalysisValidationException)e).code()).isEqualTo("VECTOR_UNAVAILABLE");return;
        }
        assertThat(jdbc.queryForObject("select extversion from pg_extension where extname='vector'",String.class)).startsWith("0.8.");
        var vector=search.search(r.id(),r.contextHash(),c.token(),request);
        assertThat(vector.path("result").get(0).path("documentId").asText()).isEqualTo(a.documentId().toString());
        assertThat(vector.path("result").get(0).path("score").asDouble()).isEqualTo(1.0);
        assertThat(search.search(r.id(),r.contextHash(),c.token(),request)).isEqualTo(vector);
        var hybrid=search.search(r.id(),r.contextHash(),c.token(),query("HYBRID","manual-fixture","1",new float[]{1,0}));
        assertThat(hybrid.path("result").get(0).path("documentId").asText()).isEqualTo(a.documentId().toString());
    }
    @Test void rankingTiesTopKAndEmptyLexicalResultsAreStable() {
        var f=ready();var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var a=publish(f,policy(f,"tie-a",1,date.minusDays(1),date.plusDays(1),"분할 청구 허용",new float[]{1,0}));
        var b=publish(f,policy(f,"tie-b",1,date.minusDays(1),date.plusDays(1),"분할 청구 허용",new float[]{1,0}));
        var first=java.util.stream.Stream.of(a.documentId().toString(),b.documentId().toString()).sorted().findFirst().orElseThrow();
        var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());
        for(String mode:policyStore.vectorAvailable()?java.util.List.of("LEXICAL","VECTOR","HYBRID"):java.util.List.of("LEXICAL")) {
            boolean vector=!mode.equals("LEXICAL");
            var request=new PolicySearchService.Request(UUID.randomUUID(),"분할",mode,vector?"manual-fixture":null,vector?"1":null,vector?new float[]{1,0}:null,1);
            var result=search.search(r.id(),r.contextHash(),c.token(),request);
            assertThat(result.path("result").size()).isEqualTo(1);
            assertThat(result.path("result").get(0).path("documentId").asText()).isEqualTo(first);
            assertThat(search.search(r.id(),r.contextHash(),c.token(),request)).isEqualTo(result);
        }
        var empty=search.search(r.id(),r.contextHash(),c.token(),new PolicySearchService.Request(UUID.randomUUID(),"없는단어","LEXICAL",null,null,null,3));
        assertThat(empty.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(empty.path("result").size()).isZero();
    }
    @Test void explicitlyConflictingPolicyMetadataIsSeparateFromMissingEvidence() {
        var f=ready();var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        for(String effect:java.util.List.of("ALLOW","DENY")) {
            var input=new PolicyCatalogService.Publish(UUID.randomUUID().toString(),caseVersion(f.caseId()),"CONTRACT-1",effect,1,
                    "분할 지침",date.minusDays(1),date.plusDays(1),"manual-fixture","1",java.util.List.of(
                    new PolicyCatalogService.ChunkInput(1,1,"분할 "+effect,new float[]{1,0},"PARTIAL_INVOICE",effect)));
            publish(f,input);
        }
        var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());
        var result=search.search(r.id(),r.contextHash(),c.token(),query("LEXICAL",null,null,null));
        assertThat(result.path("status").asText()).isEqualTo("CONFLICT");
        assertThat(result.path("conflictRules").get(0).asText()).isEqualTo("PARTIAL_INVOICE");
        assertThat(result.path("result").size()).isEqualTo(2);
    }
    @Test void policyPublicationOnAnotherCaseWaitsForTheSharedScopeReadThenStalesTheOldRun() throws Exception {
        var f=ready();var other=ready();var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        publish(f,policy(f,"guide",1,date.minusDays(1),date.plusDays(1),"분할 허용",new float[]{1,0}));
        var r=reserve(f);
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var reader=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                policyStore.lockScopeRead(PO_ID);held.countDown();
                try { assertThat(release.await(8,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
            }));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->publish(other,policy(other,"guide",2,date.minusDays(1),date.plusDays(1),"분할 금지",new float[]{1,0})));
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock'"
                    + " and query ilike '%purchase_order_snapshot%for update%')",Boolean.class))) {
                assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(30);
            }
            assertThat(writer.isDone()).isFalse();release.countDown();reader.get(10,TimeUnit.SECONDS);writer.get(10,TimeUnit.SECONDS);
        } finally { release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
        assertThat(ai.claim(r.id(),r.contextHash()).disposition()).isEqualTo("STALE");
    }
    @Test void mappingSourcesAndIdsAreIndependentAndPaperMismatchRequiresReview() {
        var f=ready("A3 용지 60 2500");var r=reserve(f);var c=ai.claim(r.id(),r.contextHash());long version=caseVersion(f.caseId());
        for(int i=0;i<2;i++) ai.reserveCall(r.id(),r.contextHash(),c.token(),UUID.randomUUID(),1000);
        var document=json.createObjectNode().put("schemaVersion","invoice-extraction-v1").put("promptVersion","invoice-advisory-1");
        document.putArray("calls").addObject().put("model","fixture-v1").put("inputTokens",100).put("outputTokens",100).put("latencyMs",1);
        var extraction=document.putObject("result");extraction.putArray("fields");extraction.putArray("warnings");
        var raw=extraction.putArray("lines").addObject().put("lineNumber",1);
        String segment=f.documents().getFirst().documentId()+":page:1";
        var source=raw.putObject("rawItemName").put("value","A3 용지").putObject("source").put("segmentId",segment).put("start",0).put("end",5);
        raw.putObject("quantity").put("value","60").putObject("source").put("segmentId",segment).put("start",6).put("end",8);
        raw.putObject("unitPrice").put("value","2500").putObject("source").put("segmentId",segment).put("start",9).put("end",13);
        assertThat(ai.checkpoint(r.id(),r.contextHash(),c.token(),"document",document)).isEqualTo("ACCEPTED");
        var context=jsonValue(c.context());var item=java.util.stream.StreamSupport.stream(context.path("items").spliterator(),false)
                .filter(i->i.path("itemId").asText().equals(ITEM_A)).findFirst().orElseThrow();
        var mapping=json.createObjectNode().put("schemaVersion","item-mapping-v1").put("promptVersion","invoice-advisory-1");
        mapping.set("calls",document.path("calls").deepCopy());var line=mapping.putObject("result").putArray("lines").addObject()
                .put("lineNumber",1).put("reviewRequired",true);line.set("source",source.deepCopy());line.putArray("warningCodes");
        var candidate=line.putArray("candidates").addObject().put("itemId","FOREIGN").put("purchaseOrderLineId",item.path("purchaseOrderLineId").asText())
                .put("reason","사람 확인이 필요한 후보").putNull("priorSnapshotId");candidate.putArray("reasonCodes").add("ALIAS");
        assertThatThrownBy(()->ai.checkpoint(r.id(),r.contextHash(),c.token(),"mapping",mapping)).isInstanceOf(AnalysisValidationException.class);
        candidate.put("itemId",ITEM_A);
        assertThatThrownBy(()->ai.checkpoint(r.id(),r.contextHash(),c.token(),"mapping",mapping)).isInstanceOf(AnalysisValidationException.class);
        candidate.putArray("reasonCodes").add("SPECIFICATION_MISMATCH");line.putArray("warningCodes").add("SPECIFICATION_MISMATCH");
        line.put("reviewRequired",false);
        assertThatThrownBy(()->ai.checkpoint(r.id(),r.contextHash(),c.token(),"mapping",mapping)).isInstanceOf(AnalysisValidationException.class);
        line.put("reviewRequired",true);source=(com.fasterxml.jackson.databind.node.ObjectNode)line.path("source");source.put("segmentId","foreign");
        assertThatThrownBy(()->ai.checkpoint(r.id(),r.contextHash(),c.token(),"mapping",mapping)).isInstanceOf(AnalysisValidationException.class);
        source.put("segmentId",segment);
        assertThat(count("proposal_step")).isEqualTo(1);
        assertThat(ai.checkpoint(r.id(),r.contextHash(),c.token(),"mapping",mapping)).isEqualTo("ACCEPTED");
        assertThat(ai.checkpoint(r.id(),r.contextHash(),UUID.randomUUID(),"mapping",mapping)).isEqualTo("REPLAYED");
        assertThat(count("proposal_step")).isEqualTo(2);assertThat(caseVersion(f.caseId())).isEqualTo(version);
        assertThat(count("receipt_allocation")).isZero();assertThat(count("payment_request")).isZero();
    }
    private com.fasterxml.jackson.databind.JsonNode jsonValue(String value) {
        try { return json.readTree(value); } catch(Exception e) { throw new AssertionError(e); }
    }
}
