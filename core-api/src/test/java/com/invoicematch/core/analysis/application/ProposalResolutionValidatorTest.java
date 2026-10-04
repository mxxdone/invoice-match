package com.invoicematch.core.analysis.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProposalResolutionValidatorTest {
    final ObjectMapper json=new ObjectMapper();final ProposalSourceCatalog sources=new ProposalSourceCatalog(json);
    final ProposalStageValidator validator=new ProposalStageValidator(sources);
    final UUID request=UUID.randomUUID(),chunk=UUID.randomUUID(),policy=UUID.randomUUID();
    ProposalRun run(boolean normal) {
        var context=json.createObjectNode();var match=context.putObject("matchResult").put("normal",normal);
        match.putArray("lineOutcomes").addObject().put("lineNumber",1).put("invoiceQuantity",60).put("invoiceUnitPrice",2500)
            .put("availableConfirmedQuantity",50).put("plannedQuantity",50);
        String canonical=AnalysisCanonicalJson.canonicalize(context);
        return new ProposalRun(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            AnalysisCanonicalJson.sha256Hex(canonical),canonical,"RUNNING",UUID.randomUUID(),Instant.now().plusSeconds(120),1,2,2000,1,true,true,null);
    }
    ObjectNode evidence(String status) {
        var n=json.createObjectNode().put("schemaVersion","ai-evidence-stage-v1");n.putArray("calls");
        var r=n.putObject("result").put("status",status);if(status.equals("NOT_REQUIRED")) r.putNull("toolRequestId");else r.put("toolRequestId",request.toString());return n;
    }
    ProposalStore.Step step(String name,ObjectNode value) {
        String c=AnalysisCanonicalJson.canonicalize(value);return new ProposalStore.Step(name,AnalysisCanonicalJson.sha256Hex(c),c);
    }
    List<ProposalStore.Step> steps(ProposalRun run,String status) {
        var document=json.createObjectNode();var result=document.putObject("result");result.putArray("fields").addObject();result.putArray("lines");result.putArray("warnings");
        var mapping=json.createObjectNode();mapping.putObject("result").putArray("lines");
        var tool=json.createObjectNode().put("schemaVersion","ai-evidence-v1").put("contextHash",run.contextHash()).put("status",status);
        tool.putObject("request").put("requestId",request.toString());
        tool.putArray("result").addObject().put("chunkId",chunk.toString()).put("documentId",policy.toString()).put("documentVersion",2)
            .put("page",3).put("paragraph",4).put("text","😀 분할 청구는 추가 검수 자료를 확인한다.");
        return List.of(step("document",document),step("mapping",mapping),step("evidence",evidence(status)),step("tool:"+request,tool));
    }
    ObjectNode resolution() {
        var n=json.createObjectNode().put("schemaVersion","ai-resolution-v1").put("promptVersion","invoice-advisory-1");
        n.putArray("calls").addObject().put("model","fixture").put("inputTokens",100).put("outputTokens",100).put("latencyMs",1);
        var r=n.putObject("result").put("recommendation","SUPPLEMENT_REQUEST").put("summary","추가 검수 자료 확인을 요청하는 초안입니다.");
        r.putArray("factIds").add("invoiceTotal");r.putArray("warnings");
        r.putArray("citations").addObject().put("chunkId",chunk.toString()).put("documentId",policy.toString()).put("documentVersion",2)
            .put("page",3).put("paragraph",4).put("start",2).put("end",7).put("quote","분할 청구");return n;
    }
    @Test void coreFactsAndUnicodeCitationAreIndependentlyVerified() {
        var run=run(false);var steps=steps(run,"FOUND");validator.validate("evidence",evidence("FOUND"),run,steps);
        validator.validate("resolution",resolution(),run,steps);
        assertThat(ProposalResolutionValidator.facts(sources.context(run)).path("invoiceTotal").path("value").asLong()).isEqualTo(150000);
    }
    @Test void forgedVersionPageQuoteMoneyFactAndActionAreRejected() {
        var run=run(false);var steps=steps(run,"FOUND");
        for(String field:List.of("documentVersion","page","paragraph","start")) {
            var n=resolution();((ObjectNode)n.path("result").path("citations").get(0)).put(field,1);reject(n,run,steps);
        }
        for(String field:List.of("chunkId","documentId","quote")) {
            var n=resolution();((ObjectNode)n.path("result").path("citations").get(0)).put(field,"forged");reject(n,run,steps);
        }
        for(String summary:List.of("금액은 150001원","금액 Ⅷ","금액 ²")) {var n=resolution();((ObjectNode)n.path("result")).put("summary",summary);reject(n,run,steps);}
        var n=resolution();((ObjectNode)n.path("result")).put("recommendation","APPROVAL_REVIEW");reject(n,run,steps);
        n=resolution();((ObjectNode)n.path("result")).putArray("factIds").add("fakeBalance");reject(n,run,steps);
        n=resolution();((ObjectNode)n.path("result")).putArray("citations");reject(n,run,steps);
        n=resolution();((ObjectNode)n.path("result")).putArray("warnings").add("POLICY_CONFLICT");reject(n,run,steps);
    }
    @Test void missingConflictAndAliasedMappingRequireReviewWithoutModelCalls() {
        var run=run(false);
        for(String status:List.of("CONFLICT","INSUFFICIENT_EVIDENCE")) {
            var steps=steps(run,status);reject(resolution(),run,steps);
            var n=resolution();n.putArray("calls");var r=(ObjectNode)n.path("result");r.putArray("citations");
            r.put("recommendation",status.equals("CONFLICT")?"REVIEW_REQUIRED":"INSUFFICIENT_EVIDENCE");
            r.putArray("warnings").add(status.equals("CONFLICT")?"POLICY_CONFLICT":"INSUFFICIENT_EVIDENCE");
            validator.validate("resolution",n,run,steps);
        }
        var steps=new ArrayList<>(steps(run,"FOUND"));var mapping=json.createObjectNode();var line=mapping.putObject("result").putArray("lines").addObject().put("reviewRequired",false);
        line.putArray("candidates").addObject().putArray("reasonCodes").add("ALIAS");steps.set(1,step("mapping",mapping));reject(resolution(),run,steps);
    }
    @Test void evidenceRequiresOwnedSearchNotAnArbitraryToolOrAChangedHash() {
        var run=run(false);var steps=steps(run,"FOUND");var n=evidence("FOUND");((ObjectNode)n.path("result")).put("toolRequestId",UUID.randomUUID().toString());
        assertThatThrownBy(()->validator.validate("evidence",n,run,steps)).isInstanceOf(AnalysisValidationException.class);
        var corrupt=new ArrayList<>(steps);var tool=steps.get(3);corrupt.set(3,new ProposalStore.Step(tool.stage(),"0".repeat(64),tool.payload()));
        assertThatThrownBy(()->validator.validate("evidence",evidence("FOUND"),run,corrupt)).isInstanceOf(AnalysisValidationException.class);
        assertThatThrownBy(()->validator.validate("evidence",evidence("NOT_REQUIRED"),run,steps)).isInstanceOf(AnalysisValidationException.class);
        var normal=run(true);validator.validate("evidence",evidence("NOT_REQUIRED"),normal,List.of());
    }
    @Test void executionPricingAndEmbeddingMustMatchFrozenPolicyVersionAndDimension() {
        var plan=json.createObjectNode().put("schemaVersion","ai-execution-plan-v1").put("model","fixture").put("providerFingerprint","0".repeat(64))
            .put("inputPricePerMillion",1).put("outputPricePerMillion",2).put("currency","USD").put("costCeiling",0.1)
            .put("tokenParameter","max_tokens").put("promptVersion","invoice-advisory-1");
        plan.putObject("embedding").put("model","embed").put("version","2").put("dimension",2).put("providerFingerprint","1".repeat(64)).put("pricePerMillion",1);
        validator.validate("execution",plan,run(false),List.of());
        var bad=plan.deepCopy();bad.put("costCeiling",0);
        assertThatThrownBy(()->validator.validate("execution",bad,run(false),List.of())).isInstanceOf(AnalysisValidationException.class);
        var original=run(false);var context=(ObjectNode)sources.context(original);
        context.putArray("policyDocuments").addObject().put("embeddingModel","embed").put("embeddingVersion","2").put("embeddingDimension",2);
        String canonical=AnalysisCanonicalJson.canonicalize(context);
        var run=new ProposalRun(original.id(),original.caseId(),original.bundleId(),original.parserRunId(),original.matchResultId(),AnalysisCanonicalJson.sha256Hex(canonical),canonical,
            original.status(),original.executionToken(),original.leaseUntil(),1,2,2000,1,true,true,null);
        var embedded=json.createObjectNode().put("schemaVersion","ai-query-embedding-v1").put("query","분할").put("model","embed").put("version","2").put("dimension",2);
        embedded.putArray("embedding").add(1).add(0);embedded.putArray("calls").addObject().put("model","embed").put("inputTokens",2).put("outputTokens",0).put("latencyMs",1);
        var steps=List.of(step("execution",plan));validator.validate("embedding",embedded,run,steps);
        for(String key:List.of("model","version","query")) {var forged=embedded.deepCopy();forged.put(key,"forged");assertThatThrownBy(()->validator.validate("embedding",forged,run,steps)).isInstanceOf(AnalysisValidationException.class);}
        var zero=embedded.deepCopy();zero.putArray("embedding").add(0).add(0);
        assertThatThrownBy(()->validator.validate("embedding",zero,run,steps)).isInstanceOf(AnalysisValidationException.class);
    }
    void reject(ObjectNode n,ProposalRun run,List<ProposalStore.Step> steps) {assertThatThrownBy(()->validator.validate("resolution",n,run,steps)).isInstanceOf(AnalysisValidationException.class);}
}
