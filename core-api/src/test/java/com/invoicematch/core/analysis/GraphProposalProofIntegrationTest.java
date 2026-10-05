package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.support.TestActors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** P4-06 graph completion must be freezable and approvable through the shared assembly boundary. */
class GraphProposalProofIntegrationTest extends AbstractGraphReviewIntegrationTest {
    @Autowired GraphProposalAssembler graphProposals;
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
}
