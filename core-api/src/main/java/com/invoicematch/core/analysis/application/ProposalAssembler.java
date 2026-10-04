package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.util.List;
import org.springframework.stereotype.Component;

/** Deterministic reconstruction from the exact frozen input and immutable checkpoints. */
@Component
public class ProposalAssembler {
    final ProposalSourceCatalog catalog;final ProposalStageValidator stages;
    public ProposalAssembler(ProposalSourceCatalog catalog,ProposalStageValidator stages) {this.catalog=catalog;this.stages=stages;}
    public record Payload(String canonical,String hash) {}
    public Payload assemble(ProposalRun run,List<ProposalStore.Step> steps) {
        var checkpoints=steps;
        for(var step:steps) {
            var payload=ProposalResolutionValidator.step(step.stage(),steps,catalog);
            if(!step.stage().startsWith("tool:") && !List.of("document","mapping","evidence","resolution").contains(step.stage())) stages.validate(step.stage(),payload,run,steps);
        }
        var output=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("schemaVersion","advisory-proposal-v1").put("proposalId",run.id().toString()).put("contextHash",run.contextHash())
                .put("evidenceBundleId",run.bundleId().toString()).put("matchResultId",run.matchResultId().toString());
        int calls=0,tokens=0;
        for(String stage:List.of("document","mapping","evidence","resolution")) {
            var payload=ProposalResolutionValidator.step(stage,checkpoints,catalog);
            stages.validate(stage,payload,run,checkpoints);output.set(stage,payload);
        }
        for(var step:checkpoints) {
            if(step.stage().startsWith("tool:")) continue;
            var payload=catalog.parse(step.payload());
            for(var call:payload.path("calls")) { calls++;tokens+=call.path("inputTokens").asInt()+call.path("outputTokens").asInt(); }
        }
        if(calls>run.reservedCalls() || tokens>run.reservedTokens()) throw ProposalSourceCatalog.invalid();
        var plan=checkpoints.stream().filter(s->s.stage().equals("execution")).findFirst();
        if(plan.isPresent()) output.set("execution",ProposalResolutionValidator.step("execution",checkpoints,catalog));
        var embedding=checkpoints.stream().filter(s->s.stage().equals("embedding")).findFirst();
        if(embedding.isPresent()) {var e=ProposalResolutionValidator.step("embedding",checkpoints,catalog);stages.validate("embedding",e,run,checkpoints);output.set("embedding",e);}
        var facts=ProposalResolutionValidator.facts(catalog.context(run));
        facts.fieldNames().forEachRemaining(name->{var fact=(com.fasterxml.jackson.databind.node.ObjectNode)facts.get(name);fact.put("value",fact.path("value").asText());});
        output.set("facts",facts);
        output.set("policyEvidence",ProposalResolutionValidator.evidence(output.path("evidence"),run,checkpoints,catalog));
        output.put("reservedCalls",run.reservedCalls()).put("reservedTokens",run.reservedTokens()).put("toolCalls",run.toolCalls());
        String canonical=AnalysisCanonicalJson.canonicalize(output);
        if(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>120000) throw new AnalysisValidationException("AI_INPUT_LIMIT","Advisory proposal exceeds the limit");
        return new Payload(canonical,AnalysisCanonicalJson.sha256Hex(canonical));
    }
}
