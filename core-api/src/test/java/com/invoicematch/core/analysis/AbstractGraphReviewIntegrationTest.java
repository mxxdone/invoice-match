package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.IdempotencyConflictException;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
abstract class AbstractGraphReviewIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @org.junit.jupiter.api.BeforeEach void resetGraphPolicyCatalog() {jdbc.execute("truncate table policy_contract cascade");}
    @DynamicPropertySource static void graphProperties(DynamicPropertyRegistry r) {r.add("analysis.graph.enabled",()->true);r.add("analysis.graph.cost-ceiling",()->"1");}
    @Autowired GraphExecutionService graph;
    @Autowired GraphStageService stages;
    @Autowired GraphReviewService reviews;
    @Autowired GraphStore store;
    @Autowired GraphDeliveryService delivery;
    @Autowired com.invoicematch.core.analysis.persistence.GraphDeliveryStore deliveries;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    protected record Fixture(RunFixture parser,GraphExecutionService.Reserved graph,GraphExecutionService.Waiting waiting,ObjectNode confirmation) {}
    protected ObjectNode stage(String schema) {
        var n=json.createObjectNode().put("schemaVersion",schema).put("promptVersion","invoice-advisory-1");
        n.putArray("calls").addObject().put("model","fixture").put("inputTokens",100).put("outputTokens",100).put("latencyMs",1);return n;
    }
    protected Fixture waiting(int candidates) {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"paper 60 2500"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        var r=TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
        var claim=graph.claim(r.id(),r.contextHash());var t=claim.token();
        var plan=json.createObjectNode().put("schemaVersion","ai-execution-plan-v1").put("model","fixture").put("providerFingerprint","1".repeat(64))
            .put("inputPricePerMillion",1).put("outputPricePerMillion",2).put("currency","USD").put("costCeiling",1)
            .put("tokenParameter","max_completion_tokens").put("promptVersion","invoice-advisory-1");plan.putNull("embedding");
        stages.save(r.id(),r.contextHash(),t,"execution",plan);stages.reserve(r.id(),r.contextHash(),t,UUID.randomUUID(),2000);
        var document=stage("invoice-extraction-v1");var result=document.putObject("result");result.putArray("fields");var lines=result.putArray("lines");var warnings=result.putArray("warnings");
        if(candidates<0)warnings.add("EMPTY_DOCUMENT");
        else {
            var line=lines.addObject().put("lineNumber",1);String segment=d.documentId()+":page:1";
            line.putObject("rawItemName").put("value","paper").putObject("source").put("segmentId",segment).put("start",0).put("end",5);
            line.putObject("quantity").put("value","60").putObject("source").put("segmentId",segment).put("start",6).put("end",8);
            line.putObject("unitPrice").put("value","2500").putObject("source").put("segmentId",segment).put("start",9).put("end",13);
        }
        var doc=stages.save(r.id(),r.contextHash(),t,"document",document);var mapping=stage("item-mapping-v1");
        var mapped=mapping.putObject("result").putArray("lines");
        if(candidates<0)mapping.putArray("calls");
        else {
            stages.reserve(r.id(),r.contextHash(),t,UUID.randomUUID(),2000);
            var line=mapped.addObject().put("lineNumber",1).put("reviewRequired",true);line.set("source",lines.get(0).path("rawItemName").path("source"));
            line.putArray("warningCodes").add(candidates==0?"NO_CANDIDATES":"AMBIGUOUS");var list=line.putArray("candidates");
            for(int i=0;i<candidates;i++) {
                var item=claim.context().path("items").get(i);var candidate=list.addObject().put("itemId",item.path("itemId").asText())
                    .put("purchaseOrderLineId",item.path("purchaseOrderLineId").asText()).put("reason","원문 후보 의견").putNull("priorSnapshotId");
                candidate.putArray("reasonCodes").add("ALIAS");
            }
        }
        var map=stages.save(r.id(),r.contextHash(),t,"mapping",mapping);UUID cp=UUID.randomUUID(),task=UUID.randomUUID();
        var body=json.createObjectNode().put("v",GraphRun.SCHEMA).put("id",cp.toString()).put("ts",Instant.now().toString());
        body.putObject("channel_values").put("graphExecutionId",r.id().toString()).put("contextHash",r.contextHash())
            .put("documentStageRef",doc.ref().toString()).put("mappingStageRef",map.ref().toString());
        body.putObject("channel_versions").put("graphExecutionId",1);body.putObject("versions_seen");body.putNull("updated_channels");
        var metadata=json.createObjectNode().put("source","loop").put("step",3);metadata.putObject("parents");
        var checkpoint=graph.checkpoint(r.id(),r.contextHash(),t,new GraphCommands.Checkpoint(r.id(),GraphRun.GRAPH,GraphRun.SERIALIZER,GraphRun.SCHEMA,cp,null,encode(body),metadata,json.createObjectNode().put("graphExecutionId",1)));
        String reason=candidates<0?"DOCUMENT_REVIEW_REQUIRED":candidates==0?"NO_ITEM_CANDIDATE":"AMBIGUOUS_ITEM";
        var value=json.createObjectNode().put("graphExecutionId",r.id().toString()).put("documentStageRef",doc.ref().toString()).put("mappingStageRef",map.ref().toString());value.putArray("reasonCodes").add(reason);
        var interrupt=json.createObjectNode().put("id","a".repeat(32));interrupt.set("value",encode(value));
        var payload=json.createArrayNode().add("tuple").add(json.createArrayNode().add(json.createArrayNode().add("interrupt").add(interrupt)));
        var writes=graph.writes(r.id(),r.contextHash(),t,List.of(new GraphCommands.Write(cp,task,-3,1,null,"__interrupt__","~__pregel_pull, human",payload)));
        var wait=graph.waitForHuman(r.id(),r.contextHash(),t,new GraphCommands.Wait(cp,checkpoint.hash(),task,1,writes.getFirst().hash(),"a".repeat(32)));
        var confirmation=json.createObjectNode().put("documentStageRef",doc.ref().toString()).put("mappingStageRef",map.ref().toString()).put("documentDecision",candidates<0?"CONFIRMED":"NOT_REQUIRED");
        var choices=confirmation.putArray("itemDecisions");
        if(candidates>=0) {
            var choice=choices.addObject().put("lineNumber",1);choice.set("source",mapped.get(0).path("source").deepCopy());
            if(candidates==0)choice.putNull("itemId").putNull("purchaseOrderLineId");
            else {var candidate=mapped.get(0).path("candidates").get(0);choice.set("itemId",candidate.get("itemId"));choice.set("purchaseOrderLineId",candidate.get("purchaseOrderLineId"));}
        }
        return new Fixture(f,r,wait,confirmation);
    }
    protected JsonNode encode(JsonNode value) {
        if(value.isObject()) {var obj=json.createObjectNode();value.properties().forEach(e->obj.set(e.getKey(),encode(e.getValue())));return json.createArrayNode().add("dict").add(obj);}
        if(value.isArray()) {var array=json.createArrayNode();value.forEach(v->array.add(encode(v)));return json.createArrayNode().add("list").add(array);}
        return json.createArrayNode().add("scalar").add(value);
    }
    protected GraphReviewService.Command command(Fixture f,String request) {return new GraphReviewService.Command(request,caseVersion(f.parser().caseId()),f.waiting().interruptId(),f.waiting().checkpointHash(),1,f.confirmation().deepCopy(),"원문과 후보를 확인했습니다.");}
    protected CommandResult<GraphReviewService.Saved> confirm(Fixture f,GraphReviewService.Command c) {return TestActors.call("operator","OPERATOR",()->reviews.confirm(f.parser().caseId(),f.graph().id(),c));}
}
