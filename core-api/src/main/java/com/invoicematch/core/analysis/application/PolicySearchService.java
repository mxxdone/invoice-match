package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fenced v1 policy-search calls; owns budget admission, replay and immutable result storage. */
@Service
public class PolicySearchService {
    private final ProposalExecutionService execution;
    private final ProposalStore runs;
    private final FrozenPolicySearch frozen;
    private final ProposalSourceCatalog sources;
    private final ObjectMapper mapper;
    public PolicySearchService(ProposalExecutionService execution,ProposalStore runs,FrozenPolicySearch frozen,
            ProposalSourceCatalog sources,ObjectMapper mapper) {
        this.execution=execution;this.runs=runs;this.frozen=frozen;this.sources=sources;this.mapper=mapper;
    }
    public record Request(UUID requestId,String query,String mode,String embeddingModel,String embeddingVersion,float[] embedding,int limit) {}
    @Transactional
    public JsonNode search(UUID id,String hash,UUID token,Request request) {
        FrozenPolicySearch.validateRequest(request);
        var run=execution.active(id,hash,token);String stage="tool:"+request.requestId();
        JsonNode arguments=mapper.valueToTree(request);
        var existing=runs.steps(id).stream().filter(s->s.stage().equals(stage)).findFirst();
        if(existing.isPresent()) {
            var saved=sources.parse(existing.get().payload());
            if(!AnalysisCanonicalJson.canonicalize(saved.path("request")).equals(AnalysisCanonicalJson.canonicalize(arguments))) throw ProposalExecutionService.conflict("TOOL_CONFLICT");
            return saved;
        }
        if(run.toolCalls()>=ProposalRun.MAX_TOOLS) throw ProposalExecutionService.conflict("AI_BUDGET_EXHAUSTED");
        var output=frozen.readFrozen(run,request);
        String canonical=AnalysisCanonicalJson.canonicalize(output);
        runs.countTool(id);runs.step(id,stage,canonical,AnalysisCanonicalJson.sha256Hex(canonical));return sources.parse(canonical);
    }
    static void validateEmbedding(String model,float[] vector) {
        if(model==null || model.isBlank() || model.length()>100 || model.chars().anyMatch(Character::isISOControl)
                || vector==null || vector.length<1 || vector.length>3072) throw invalid("AI_EMBEDDING_MISMATCH");
        double norm=0;for(float value:vector) { if(!Float.isFinite(value) || Math.abs(value)>1000000) throw invalid("AI_EMBEDDING_MISMATCH");norm+=(double)value*value; }
        if(norm<1e-12 || norm>1e12) throw invalid("AI_EMBEDDING_MISMATCH");
    }
    static AnalysisValidationException invalid(String code) { return new AnalysisValidationException(code,"Invalid policy scope, query or embedding"); }
}
