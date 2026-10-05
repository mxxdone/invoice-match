package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import com.invoicematch.core.support.TestActors;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** P4-06 graph completion must be freezable and approvable through the shared assembly boundary. */
class GraphProposalProofIntegrationTest extends AbstractGraphReviewIntegrationTest {
    @Autowired GraphProposalAssembler graphProposals;
    @Autowired ProposalAssembler proposalAssembler;
    @Autowired GraphProposalProofService graphProof;
    @Autowired com.invoicematch.core.approval.application.ApprovalApplicationService approval;

    private record CompletedGraph(UUID caseId, UUID runId, String payloadHash, UUID bundleId, UUID matchId) {}
    private CompletedGraph completedGraph() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"paper 60 2500"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        var r=TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),
            new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
        var claimed=graph.claim(r.id(),r.contextHash());var t=claimed.token();
        var plan=json.createObjectNode().put("schemaVersion","ai-execution-plan-v1").put("model","fixture").put("providerFingerprint","1".repeat(64))
            .put("inputPricePerMillion",1).put("outputPricePerMillion",2).put("currency","USD").put("costCeiling",1)
            .put("tokenParameter","max_completion_tokens").put("promptVersion","invoice-advisory-1");plan.putNull("embedding");
        stages.save(r.id(),r.contextHash(),t,"execution",plan);stages.reserve(r.id(),r.contextHash(),t,UUID.randomUUID(),2000);
        var document=stage("invoice-extraction-v1");var result=document.putObject("result");result.putArray("fields");
        var lines=result.putArray("lines");result.putArray("warnings");var line=lines.addObject().put("lineNumber",1);
        String segment=d.documentId()+":page:1";
        line.putObject("rawItemName").put("value","paper").putObject("source").put("segmentId",segment).put("start",0).put("end",5);
        line.putObject("quantity").put("value","60").putObject("source").put("segmentId",segment).put("start",6).put("end",8);
        line.putObject("unitPrice").put("value","2500").putObject("source").put("segmentId",segment).put("start",9).put("end",13);
        stages.save(r.id(),r.contextHash(),t,"document",document);
        var mapping=stage("item-mapping-v1");var mapped=mapping.putObject("result").putArray("lines");
        stages.reserve(r.id(),r.contextHash(),t,UUID.randomUUID(),2000);
        var mappedLine=mapped.addObject().put("lineNumber",1).put("reviewRequired",true);
        mappedLine.set("source",lines.get(0).path("rawItemName").path("source"));
        mappedLine.putArray("warningCodes");var candidates=mappedLine.putArray("candidates");
        var item=claimed.context().path("items").get(0);
        candidates.addObject().put("itemId",item.path("itemId").asText()).put("purchaseOrderLineId",item.path("purchaseOrderLineId").asText())
            .put("reason","원문 후보 의견").putNull("priorSnapshotId").putArray("reasonCodes").add("ALIAS");
        stages.save(r.id(),r.contextHash(),t,"mapping",mapping);
        boolean normal=claimed.context().path("matchResult").path("normal").asBoolean();
        var evidence=json.createObjectNode().put("schemaVersion","ai-evidence-stage-v1");evidence.putArray("calls");
        evidence.putObject("result").put("status",normal?"NOT_REQUIRED":"INSUFFICIENT_EVIDENCE").putNull("toolRequestId");
        stages.save(r.id(),r.contextHash(),t,"evidence",evidence);
        var resolution=json.createObjectNode().put("schemaVersion","ai-resolution-v1").put("promptVersion","invoice-advisory-1");resolution.putArray("calls");
        var resolutionResult=resolution.putObject("result").put("recommendation",normal?"REVIEW_REQUIRED":"INSUFFICIENT_EVIDENCE")
            .put("summary","원문과 근거를 사람이 검토해야 합니다.");
        resolutionResult.putArray("factIds").add("invoiceTotal");resolutionResult.putArray("citations");
        resolutionResult.putArray("warnings").add("MAPPING_REVIEW");
        stages.save(r.id(),r.contextHash(),t,"resolution",resolution);
        stages.complete(r.id(),r.contextHash(),t);
        var saved=store.result(r.id()).orElseThrow();
        var run=store.read(r.id()).orElseThrow();
        return new CompletedGraph(f.caseId(),r.id(),saved.hash(),run.evidenceBundleId(),run.matchResultId());
    }
    private void freeze(CompletedGraph g, String requestId, UUID proposalId, String hash) {
        TestActors.run("approver","APPROVER",()->reviewService.freezeSnapshot(
            new FreezeReviewSnapshotCommand(g.caseId(),requestId,caseVersion(g.caseId()),proposalId,hash)));
    }
    private com.invoicematch.core.review.domain.ReviewSnapshot latestSnapshot(UUID caseId) {
        return reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId).orElseThrow();
    }
    private void approve(CompletedGraph g, String requestId) {
        var snapshot=latestSnapshot(g.caseId());
        TestActors.run("approver","APPROVER",()->approval.approve(new com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand(
            g.caseId(),requestId,caseVersion(g.caseId()),snapshot.id(),snapshot.payloadHash())));
    }

    @Test void completedGraphProposalReassemblesIdenticallyFreezesAndApproves() throws Exception {
        var g=completedGraph();
        var run=store.read(g.runId()).orElseThrow();
        var stored=store.result(g.runId()).orElseThrow();
        var assembled=graphProposals.assemble(run,store.advisoryInput(g.runId()),store.validationSteps(g.runId()));
        assertThat(assembled.hash()).isEqualTo(g.payloadHash());
        assertThat(json.readTree(assembled.canonical())).isEqualTo(json.readTree(stored.payload()));
        assertThat(json.readTree(stored.payload()).path("schemaVersion").asText()).isEqualTo("advisory-proposal-v2");
        var proof=new TransactionTemplate(transactions).execute(tx->TestActors.call("operator","OPERATOR",()->
            graphProof.verify(g.caseId(),g.bundleId(),g.matchId(),g.runId(),g.payloadHash())));
        assertThat(proof.contextHash()).isEqualTo(run.contextHash());
        freeze(g,"freeze",g.runId(),g.payloadHash());
        var snapshot=latestSnapshot(g.caseId());
        var reference=json.readTree(snapshot.payload()).path("proposal");
        assertThat(reference.path("id").asText()).isEqualTo(g.runId().toString());
        assertThat(reference.path("payloadHash").asText()).isEqualTo(g.payloadHash());
        assertThat(reference.path("contextHash").asText()).isEqualTo(run.contextHash());
        approve(g,"approve");
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("receipt_allocation")).isEqualTo(1);
    }

    @Test void secondApprovalOfTheSameOldSnapshotIsRejectedWithNoExtraEffects() {
        var g=completedGraph();freeze(g,"freeze",g.runId(),g.payloadHash());approve(g,"approve-1");
        var snapshot=latestSnapshot(g.caseId());
        assertThatThrownBy(()->TestActors.run("approver","APPROVER",()->approval.approve(
                new com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand(g.caseId(),"approve-2",caseVersion(g.caseId()),
                    snapshot.id(),snapshot.payloadHash())))).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("receipt_allocation")).isEqualTo(1);
    }

    @Test void wrongCaseHashWaitingAndChangedInputProofsAreRejectedWithoutEffects() {
        var g=completedGraph();
        var other=completedGraph();
        for(var attempt:java.util.List.<Runnable>of(
                ()->freeze(g,"cross-case",other.runId(),other.payloadHash()),
                ()->freeze(g,"wrong-hash",g.runId(),"0".repeat(64)),
                ()->freeze(g,"incomplete",waiting(-1).graph().id(),"0".repeat(64)))) {
            assertThatThrownBy(attempt::run).isInstanceOf(RuntimeException.class);
        }
        // A new match result makes the stored graph completion stale at freeze and approval time.
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(g.caseId(),"changed")));
        assertThatThrownBy(()->freeze(g,"stale",g.runId(),g.payloadHash())).isInstanceOf(RuntimeException.class);
        assertThat(count("review_snapshot")).isZero();
        assertThat(count("payment_request")).isZero();
        assertThat(count("receipt_allocation")).isZero();
    }

    private JsonNode resumeEvent(Fixture f) throws Exception {
        return json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));
    }
    private record ResumeReady(Fixture fixture,UUID token,UUID reviewId,String documentRef,String mappingRef,String resolutionRef) {}
    private ResumeReady resumeReady() throws Exception {
        var f=waiting(-1);
        var saved=confirm(f,command(f,"resume"));
        var claim=delivery.claimResume(f.graph().id(),f.graph().contextHash(),resumeEvent(f));
        var token=claim.token();
        assertThat(json.readTree(store.advisoryInput(f.graph().id()).context()).path("matchResult").path("normal").asBoolean()).isTrue();
        var evidence=json.createObjectNode().put("schemaVersion","ai-evidence-stage-v1");evidence.putArray("calls");
        evidence.putObject("result").put("status","NOT_REQUIRED").putNull("toolRequestId");
        stages.save(f.graph().id(),f.graph().contextHash(),token,"evidence",evidence);
        var resolution=json.createObjectNode().put("schemaVersion","ai-resolution-v1").put("promptVersion","invoice-advisory-1");resolution.putArray("calls");
        var resolutionResult=resolution.putObject("result").put("recommendation","REVIEW_REQUIRED").put("summary","원문과 근거를 사람이 검토해야 합니다.");
        resolutionResult.putArray("factIds").add("invoiceTotal");resolutionResult.putArray("citations");
        resolutionResult.putArray("warnings").add("DOCUMENT_REVIEW");
        stages.save(f.graph().id(),f.graph().contextHash(),token,"resolution",resolution);
        var savedStages=store.stages(f.graph().id());
        String documentRef=savedStages.stream().filter(s->s.stage().equals("document")).findFirst().orElseThrow().id().toString();
        String mappingRef=savedStages.stream().filter(s->s.stage().equals("mapping")).findFirst().orElseThrow().id().toString();
        String resolutionRef=savedStages.stream().filter(s->s.stage().equals("resolution")).findFirst().orElseThrow().id().toString();
        return new ResumeReady(f,token,saved.body().reviewId(),documentRef,mappingRef,resolutionRef);
    }
    private ObjectNode resumeBody(UUID checkpointId,ResumeReady ready,String resolutionRef) {
        var values=json.createObjectNode().put("v",GraphRun.SCHEMA).put("id",checkpointId.toString()).put("ts",Instant.now().toString());
        values.putObject("channel_values").put("graphExecutionId",ready.fixture().graph().id().toString())
            .put("contextHash",ready.fixture().graph().contextHash()).put("documentStageRef",ready.documentRef())
            .put("mappingStageRef",ready.mappingRef()).put("reviewRef",ready.reviewId().toString()).put("resolutionStageRef",resolutionRef);
        values.putObject("channel_versions").put("graphExecutionId",1);values.putObject("versions_seen");values.putNull("updated_channels");
        return values;
    }
    private ObjectNode resumeMetadata() {
        var metadata=json.createObjectNode().put("source","loop").put("step",3);metadata.putObject("parents");return metadata;
    }
    private CompletedGraph completedResumeGraph() throws Exception {
        var ready=resumeReady();var f=ready.fixture();var checkpointId=UUID.randomUUID();
        graph.checkpoint(f.graph().id(),f.graph().contextHash(),ready.token(),new GraphCommands.Checkpoint(f.graph().id(),GraphRun.GRAPH,
            GraphRun.SERIALIZER,GraphRun.SCHEMA,checkpointId,f.waiting().checkpointId(),encode(resumeBody(checkpointId,ready,ready.resolutionRef())),
            resumeMetadata(),json.createObjectNode().put("graphExecutionId",1)));
        stages.complete(f.graph().id(),f.graph().contextHash(),ready.token());
        var result=store.result(f.graph().id()).orElseThrow();var run=store.read(f.graph().id()).orElseThrow();
        return new CompletedGraph(f.parser().caseId(),f.graph().id(),result.hash(),run.evidenceBundleId(),run.matchResultId());
    }

    @Test void humanResumeCompletionReassemblesFreezesAndApproves() throws Exception {
        var g=completedResumeGraph();
        var run=store.read(g.runId()).orElseThrow();var stored=store.result(g.runId()).orElseThrow();
        var assembled=graphProposals.assemble(run,store.advisoryInput(g.runId()),store.validationSteps(g.runId()));
        assertThat(assembled.hash()).isEqualTo(g.payloadHash());
        assertThat(json.readTree(stored.payload()).path("schemaVersion").asText()).isEqualTo("advisory-proposal-v2");
        assertThat(json.readTree(stored.payload()).path("humanReview").path("reviewId").asText())
            .isEqualTo(jdbc.queryForObject("select review_id from graph_resume_consumption where run_id=?",UUID.class,g.runId()).toString());
        freeze(g,"resume-freeze",g.runId(),g.payloadHash());
        approve(g,"resume-approve");
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("receipt_allocation")).isEqualTo(1);
    }

    @Test void finalCheckpointWithDifferentResolutionStageRefIsRejected() throws Exception {
        var ready=resumeReady();var f=ready.fixture();var checkpointId=UUID.randomUUID();
        var command=new GraphCommands.Checkpoint(f.graph().id(),GraphRun.GRAPH,GraphRun.SERIALIZER,GraphRun.SCHEMA,checkpointId,
            f.waiting().checkpointId(),encode(resumeBody(checkpointId,ready,ready.documentRef())),resumeMetadata(),
            json.createObjectNode().put("graphExecutionId",1));
        jdbc.update("insert into graph_checkpoint(run_id,checkpoint_id,graph_version,parent_id,envelope,payload_hash,execution_token)"
            + " values(?,?,?,?,cast(? as jsonb),?,?)",f.graph().id(),checkpointId,GraphRun.GRAPH,f.waiting().checkpointId(),
            json.writeValueAsString(command),"b".repeat(64),ready.token());
        assertThatThrownBy(()->stages.complete(f.graph().id(),f.graph().contextHash(),ready.token()))
            .isInstanceOf(AnalysisConflictException.class);
    }

    @Test void forgedResumeSnapshotReferenceIsRejectedWithZeroApprovalEffects() throws Exception {
        var g=completedResumeGraph();
        freeze(g,"resume-freeze",g.runId(),g.payloadHash());
        var real=latestSnapshot(g.caseId());long version=caseVersion(g.caseId());int number=real.snapshotNumber();int payments=count("payment_request");
        for(String field:List.of("id","payloadHash","contextHash")) {
            var payload=(ObjectNode)json.readTree(real.payload());
            ((ObjectNode)payload.path("proposal")).put(field,field.equals("id")?UUID.randomUUID().toString():"0".repeat(64));
            String canonical=json.writeValueAsString(sortedJson(payload));String hash=sha256(canonical);UUID forged=UUID.randomUUID();
            jdbc.update("insert into review_snapshot(id,invoice_case_id,evidence_bundle_id,match_result_id,match_result_number,snapshot_number,"
                + "target_case_version,target_evidence_bundle_version,purchasing_snapshot_version,purchasing_snapshot_hash,mapping_watermark,"
                + "payload_hash,payload,created_at) select ?,invoice_case_id,evidence_bundle_id,match_result_id,match_result_number,?,"
                + "target_case_version,target_evidence_bundle_version,purchasing_snapshot_version,purchasing_snapshot_hash,mapping_watermark,"
                + "?,cast(? as jsonb),clock_timestamp() from review_snapshot where id=?",forged,++number,hash,canonical,real.id());
            assertThatThrownBy(()->TestActors.run("approver","APPROVER",()->approval.approve(
                    new com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand(g.caseId(),UUID.randomUUID().toString(),version,forged,hash))))
                .isInstanceOf(ReviewStateConflictException.class);
            assertThat(count("payment_request")).isEqualTo(payments);
            assertThat(caseVersion(g.caseId())).isEqualTo(version);
        }
    }

    @Test void immutableReviewConsumptionAndCheckpointRowsRejectTampering() throws Exception {
        var g=completedResumeGraph();
        assertDatabaseRejects("P0001",()->jdbc.update("update graph_review set reason='changed' where run_id=?",g.runId()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_review where run_id=?",g.runId()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_resume_consumption where run_id=?",g.runId()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_checkpoint where run_id=?",g.runId()));
    }

    @Test void directlyStoredStartCompletionWithHumanRequiredStagesIsRejectedByProof() throws Exception {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"paper 60 2500"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        var reserved=TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),
            new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
        var token=graph.claim(reserved.id(),reserved.contextHash()).token();
        var plan=json.createObjectNode().put("schemaVersion","ai-execution-plan-v1").put("model","fixture").put("providerFingerprint","1".repeat(64))
            .put("inputPricePerMillion",1).put("outputPricePerMillion",2).put("currency","USD").put("costCeiling",1)
            .put("tokenParameter","max_completion_tokens").put("promptVersion","invoice-advisory-1");plan.putNull("embedding");
        stages.save(reserved.id(),reserved.contextHash(),token,"execution",plan);stages.reserve(reserved.id(),reserved.contextHash(),token,UUID.randomUUID(),2000);
        var document=stage("invoice-extraction-v1");var result=document.putObject("result");
        result.putArray("fields");result.putArray("lines");result.putArray("warnings").add("EMPTY_DOCUMENT");
        stages.save(reserved.id(),reserved.contextHash(),token,"document",document);
        var mapping=stage("item-mapping-v1");mapping.putArray("calls");mapping.putObject("result").putArray("lines");
        stages.save(reserved.id(),reserved.contextHash(),token,"mapping",mapping);
        var evidence=json.createObjectNode().put("schemaVersion","ai-evidence-stage-v1");evidence.putArray("calls");
        evidence.putObject("result").put("status","NOT_REQUIRED").putNull("toolRequestId");
        stages.save(reserved.id(),reserved.contextHash(),token,"evidence",evidence);
        var resolution=json.createObjectNode().put("schemaVersion","ai-resolution-v1").put("promptVersion","invoice-advisory-1");resolution.putArray("calls");
        var resolutionResult=resolution.putObject("result").put("recommendation","REVIEW_REQUIRED").put("summary","원문과 근거를 사람이 검토해야 합니다.");
        resolutionResult.putArray("factIds").add("invoiceTotal");resolutionResult.putArray("citations");
        resolutionResult.putArray("warnings").add("DOCUMENT_REVIEW");
        stages.save(reserved.id(),reserved.contextHash(),token,"resolution",resolution);
        // Direct DB origin: store the v2 completion the unguarded v1 assembly would produce.
        var run=store.read(reserved.id()).orElseThrow();
        var assembled=proposalAssembler.assemble(store.advisoryInput(reserved.id()),store.validationSteps(reserved.id()));
        var payload=(ObjectNode)json.readTree(assembled.canonical());
        payload.put("schemaVersion","advisory-proposal-v2").put("graphVersion",GraphRun.GRAPH);
        String payloadJson=json.writeValueAsString(sortedJson(payload)),hash=sha256(payloadJson);
        store.complete(reserved.id(),payloadJson,hash,token);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->TestActors.call("operator","OPERATOR",()->
            graphProof.verify(f.caseId(),run.evidenceBundleId(),run.matchResultId(),reserved.id(),hash))))
            .isInstanceOf(ReviewStateConflictException.class);
        assertThat(count("review_snapshot")).isZero();
    }

    private JsonNode sortedJson(JsonNode node) {
        if(node.isObject()) {
            var sorted=json.createObjectNode();var keys=new java.util.TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);
            for(String key:keys) sorted.set(key,sortedJson(node.get(key)));
            return sorted;
        }
        if(node.isArray()) {var sorted=json.createArrayNode();for(var value:node) sorted.add(sortedJson(value));return sorted;}
        return node;
    }
    private String sha256(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
