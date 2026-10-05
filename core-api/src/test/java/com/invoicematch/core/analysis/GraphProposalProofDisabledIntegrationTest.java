package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The stored graph proof is verified without the graph runtime being enabled. */
class GraphProposalProofDisabledIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @Autowired GraphStore graphStore;
    @Autowired GraphProposalAssembler graphProposals;
    @Autowired GraphProposalProofService graphProof;
    @Autowired ProposalContextFactory contexts;
    @Autowired ProposalStore inputs;
    @Autowired GraphProperties graphProperties;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private record Seed(UUID id, UUID bundleId, UUID matchId, String contextHash, String payloadHash) {}

    private ObjectMapper mapper() {return json;}
    private String canonical(JsonNode node) {
        try {return mapper().writeValueAsString(sort(node));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException(e);}
    }
    private String sha256(String value) {
        try {return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private JsonNode sort(JsonNode node) {
        if(node.isObject()) {
            List<String> names=new ArrayList<>();node.fieldNames().forEachRemaining(names::add);Collections.sort(names);
            ObjectNode sorted=mapper().createObjectNode();for(String name:names) sorted.set(name,sort(node.get(name)));return sorted;
        }
        if(node.isArray()) {ArrayNode ordered=mapper().createArrayNode();for(JsonNode element:node) ordered.add(sort(element));return ordered;}
        return node;
    }
    private ObjectNode calls(String schema) {
        var node=mapper().createObjectNode().put("schemaVersion",schema).put("promptVersion","invoice-advisory-1");
        node.putArray("calls").addObject().put("model","fixture").put("inputTokens",100).put("outputTokens",100).put("latencyMs",1);return node;
    }

    private Seed seed() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"paper 60 2500"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        return new TransactionTemplate(transactions).execute(status->{
            var source=inputs.source(f.caseId()).orElseThrow();
            var context=contexts.create(f.caseId(),source);
            String contextCanonical=canonical(context);
            UUID id=UUID.randomUUID();
            graphStore.insert(id,f.caseId(),caseVersion(f.caseId()),source,contextCanonical,sha256(contextCanonical),new BigDecimal("1"));
            UUID token=UUID.randomUUID();
            jdbc.update("update graph_run set status='RUNNING',execution_token=?,lease_until=clock_timestamp()+interval '2 minutes',start_attempts=1 where id=?",token,id);
            jdbc.update("update graph_run set reserved_calls=5,reserved_tokens=40000 where id=?",id);

            var plan=mapper().createObjectNode().put("schemaVersion","ai-execution-plan-v1").put("model","fixture").put("providerFingerprint","1".repeat(64))
                .put("inputPricePerMillion",1).put("outputPricePerMillion",2).put("currency","USD").put("costCeiling",1)
                .put("tokenParameter","max_completion_tokens").put("promptVersion","invoice-advisory-1");plan.putNull("embedding");
            save(id,"execution",plan,token);
            var document=calls("invoice-extraction-v1");var result=document.putObject("result");result.putArray("fields");
            String segment=d.documentId()+":page:1";var line=result.putArray("lines").addObject().put("lineNumber",1);
            line.putObject("rawItemName").put("value","paper").putObject("source").put("segmentId",segment).put("start",0).put("end",5);
            line.putObject("quantity").put("value","60").putObject("source").put("segmentId",segment).put("start",6).put("end",8);
            line.putObject("unitPrice").put("value","2500").putObject("source").put("segmentId",segment).put("start",9).put("end",13);
            result.putArray("warnings");save(id,"document",document,token);
            var mapping=calls("item-mapping-v1");var mappedLine=mapping.putObject("result").putArray("lines").addObject().put("lineNumber",1).put("reviewRequired",true);
            mappedLine.set("source",line.path("rawItemName").path("source"));mappedLine.putArray("warningCodes");
            var item=context.path("items").get(0);
            mappedLine.putArray("candidates").addObject().put("itemId",item.path("itemId").asText())
                .put("purchaseOrderLineId",item.path("purchaseOrderLineId").asText()).put("reason","원문 후보 의견").putNull("priorSnapshotId")
                .putArray("reasonCodes").add("ALIAS");
            save(id,"mapping",mapping,token);
            var evidence=mapper().createObjectNode().put("schemaVersion","ai-evidence-stage-v1");evidence.putArray("calls");
            evidence.putObject("result").put("status","NOT_REQUIRED").putNull("toolRequestId");save(id,"evidence",evidence,token);
            var resolution=mapper().createObjectNode().put("schemaVersion","ai-resolution-v1").put("promptVersion","invoice-advisory-1");resolution.putArray("calls");
            var resolutionResult=resolution.putObject("result").put("recommendation","REVIEW_REQUIRED").put("summary","원문과 근거를 사람이 검토해야 합니다.");
            resolutionResult.putArray("factIds").add("invoiceTotal");resolutionResult.putArray("citations");
            resolutionResult.putArray("warnings").add("MAPPING_REVIEW");save(id,"resolution",resolution,token);

            var run=graphStore.read(id).orElseThrow();
            var assembled=graphProposals.assemble(run,graphStore.advisoryInput(id),graphStore.validationSteps(id));
            graphStore.complete(id,assembled.canonical(),assembled.hash(),token);
            return new Seed(id,run.evidenceBundleId(),run.matchResultId(),run.contextHash(),assembled.hash());
        });
    }
    private void save(UUID id,String stage,ObjectNode payload,UUID token) {
        String canonical=canonical(payload);graphStore.stage(id,stage,canonical,sha256(canonical),token);
    }

    @Test void storedCompletedGraphProofVerifiesWhileGraphIsDisabled() {
        assertThat(graphProperties.enabled()).isFalse();
        var seed=seed();
        assertThat(graphStore.read(seed.id()).orElseThrow().status()).isEqualTo("COMPLETED");
        UUID caseId=jdbc.queryForObject("select invoice_case_id from graph_run where id=?",UUID.class,seed.id());
        var proof=new TransactionTemplate(transactions).execute(tx->TestActors.call("operator","OPERATOR",()->
            graphProof.verify(caseId,seed.bundleId(),seed.matchId(),seed.id(),seed.payloadHash())));
        assertThat(proof.payloadHash()).isEqualTo(seed.payloadHash());
        assertThat(proof.contextHash()).isEqualTo(seed.contextHash());
    }
}
