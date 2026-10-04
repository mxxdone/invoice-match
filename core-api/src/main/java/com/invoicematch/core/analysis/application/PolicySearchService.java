package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exact vector/lexical ranking over only the frozen, currently applicable catalog. */
@Service
public class PolicySearchService {
    private final ProposalExecutionService execution;
    private final ProposalStore runs;
    private final PolicyCatalogStore policies;
    private final ProposalSourceCatalog sources;
    private final ObjectMapper mapper;
    public PolicySearchService(ProposalExecutionService execution,ProposalStore runs,PolicyCatalogStore policies,
            ProposalSourceCatalog sources,ObjectMapper mapper) {
        this.execution=execution;this.runs=runs;this.policies=policies;this.sources=sources;this.mapper=mapper;
    }
    public record Request(UUID requestId,String query,String mode,String embeddingModel,String embeddingVersion,float[] embedding,int limit) {}
    @Transactional
    public JsonNode search(UUID id,String hash,UUID token,Request request) {
        if(request==null || request.requestId()==null || request.query()==null || request.query().isBlank()
                || request.query().length()>200 || request.query().chars().anyMatch(Character::isISOControl)
                || !Set.of("LEXICAL","VECTOR","HYBRID").contains(request.mode()==null?"":request.mode())
                || request.limit()<1 || request.limit()>10) throw invalid("TOOL_DENIED");
        boolean vector=!request.mode().equals("LEXICAL");
        if(vector) {
            validateEmbedding(request.embeddingModel(),request.embedding());
            if(request.embeddingVersion()==null || request.embeddingVersion().isBlank() || request.embeddingVersion().length()>100 || request.embeddingVersion().chars().anyMatch(Character::isISOControl)) throw invalid("AI_EMBEDDING_MISMATCH");
        }
        else if(request.embedding()!=null || request.embeddingModel()!=null || request.embeddingVersion()!=null) throw invalid("TOOL_DENIED");
        var run=execution.active(id,hash,token);String stage="tool:"+request.requestId();
        JsonNode arguments=mapper.valueToTree(request);
        var existing=runs.steps(id).stream().filter(s->s.stage().equals(stage)).findFirst();
        if(existing.isPresent()) {
            var saved=sources.parse(existing.get().payload());
            if(!AnalysisCanonicalJson.canonicalize(saved.path("request")).equals(AnalysisCanonicalJson.canonicalize(arguments))) throw ProposalExecutionService.conflict("TOOL_CONFLICT");
            return saved;
        }
        if(run.toolCalls()>=ProposalRun.MAX_TOOLS) throw ProposalExecutionService.conflict("AI_BUDGET_EXHAUSTED");
        var output=readFrozen(run,request);
        String canonical=AnalysisCanonicalJson.canonicalize(output);
        runs.countTool(id);runs.step(id,stage,canonical,AnalysisCanonicalJson.sha256Hex(canonical));return sources.parse(canonical);
    }
    /** Caller owns fencing, budget admission and immutable storage. */
    JsonNode readFrozen(ProposalRun run,Request request) {
        if(request==null || request.requestId()==null || request.query()==null || request.query().isBlank()
                || request.query().length()>200 || request.query().chars().anyMatch(Character::isISOControl)
                || !Set.of("LEXICAL","VECTOR","HYBRID").contains(request.mode()==null?"":request.mode())
                || request.limit()<1 || request.limit()>10) throw invalid("TOOL_DENIED");
        boolean vector=!request.mode().equals("LEXICAL");
        if(vector) {
            validateEmbedding(request.embeddingModel(),request.embedding());
            if(request.embeddingVersion()==null || request.embeddingVersion().isBlank() || request.embeddingVersion().length()>100 || request.embeddingVersion().chars().anyMatch(Character::isISOControl)) throw invalid("AI_EMBEDDING_MISMATCH");
        }
        else if(request.embedding()!=null || request.embeddingModel()!=null || request.embeddingVersion()!=null) throw invalid("TOOL_DENIED");
        JsonNode arguments=mapper.valueToTree(request);
        var context=sources.context(run);var documents=context.path("policyDocuments");
        Map<UUID,JsonNode> metadata=new LinkedHashMap<>();
        for(var document:documents) {
            if(vector && (!request.embeddingModel().equals(document.path("embeddingModel").asText())
                    || !request.embeddingVersion().equals(document.path("embeddingVersion").asText())
                    || request.embedding().length!=document.path("embeddingDimension").asInt())) throw invalid("AI_EMBEDDING_MISMATCH");
            metadata.put(UUID.fromString(document.path("id").asText()),document);
        }
        var ids=new ArrayList<>(metadata.keySet());
        if(vector && !ids.isEmpty() && !policies.vectorAvailable()) throw invalid("VECTOR_UNAVAILABLE");
        // Fetch at most ten times top-k per ranking; RRF is bounded and stable.
        int window=Math.min(100,request.limit()*10);
        var lexical=request.mode().equals("VECTOR")?List.<PolicyCatalogStore.Hit>of():policies.lexical(ids,request.query(),window);
        var vectors=vector?policies.vector(ids,request.embeddingModel(),request.embeddingVersion(),request.embedding().length,request.embedding(),window):List.<PolicyCatalogStore.Hit>of();
        List<PolicyCatalogStore.Hit> hits=request.mode().equals("LEXICAL")?lexical:request.mode().equals("VECTOR")?vectors:hybrid(lexical,vectors);
        var conflicts=policies.conflicts(ids);
        if(conflicts.size()>10) throw invalid("AI_INPUT_LIMIT");
        var output=mapper.createObjectNode().put("schemaVersion","ai-evidence-v1").put("contextHash",run.contextHash())
                .put("status",!conflicts.isEmpty()?"CONFLICT":hits.isEmpty()?"INSUFFICIENT_EVIDENCE":"FOUND");
        output.set("conflictRules",mapper.valueToTree(conflicts));output.set("request",arguments);
        var result=output.putArray("result");
        for(var hit:hits.subList(0,Math.min(request.limit(),hits.size()))) {
            var chunk=hit.chunk();var doc=metadata.get(chunk.documentId());
            if(doc==null || !Double.isFinite(hit.score())) throw invalid("AI_SCHEMA_INVALID");
            var entry=result.addObject().put("chunkId",chunk.id().toString()).put("documentId",chunk.documentId().toString())
                    .put("documentVersion",doc.path("version").asInt()).put("documentHash",doc.path("payloadHash").asText())
                    .put("contractId",doc.path("contractId").asText()).put("page",chunk.page()).put("paragraph",chunk.paragraph())
                    .put("text",chunk.content()).put("chunkHash",chunk.payloadHash()).put("score",hit.score());
            entry.put("title",doc.path("title").asText()).put("ruleKey",chunk.ruleKey()).put("effect",chunk.effect());
        }
        String canonical=AnalysisCanonicalJson.canonicalize(output);
        if(canonical.getBytes(StandardCharsets.UTF_8).length>40000) throw invalid("AI_INPUT_LIMIT");
        return sources.parse(canonical);
    }
    private static List<PolicyCatalogStore.Hit> hybrid(List<PolicyCatalogStore.Hit> lexical,List<PolicyCatalogStore.Hit> vectors) {
        Map<UUID,PolicyCatalogStore.Chunk> chunks=new HashMap<>();Map<UUID,Double> scores=new HashMap<>();
        for(var ranking:List.of(lexical,vectors)) for(int i=0;i<ranking.size();i++) {
            var chunk=ranking.get(i).chunk();chunks.put(chunk.id(),chunk);scores.merge(chunk.id(),1.0/(60+i+1),Double::sum);
        }
        return chunks.values().stream().map(c->new PolicyCatalogStore.Hit(c,scores.get(c.id())))
                .sorted(Comparator.comparingDouble(PolicyCatalogStore.Hit::score).reversed()
                        .thenComparing(h->h.chunk().documentId().toString()).thenComparingInt(h->h.chunk().page())
                        .thenComparingInt(h->h.chunk().paragraph()).thenComparing(h->h.chunk().id().toString())).toList();
    }
    static void validateEmbedding(String model,float[] vector) {
        if(model==null || model.isBlank() || model.length()>100 || model.chars().anyMatch(Character::isISOControl)
                || vector==null || vector.length<1 || vector.length>3072) throw invalid("AI_EMBEDDING_MISMATCH");
        double norm=0;for(float value:vector) { if(!Float.isFinite(value) || Math.abs(value)>1000000) throw invalid("AI_EMBEDDING_MISMATCH");norm+=(double)value*value; }
        if(norm<1e-12 || norm>1e12) throw invalid("AI_EMBEDDING_MISMATCH");
    }
    static AnalysisValidationException invalid(String code) { return new AnalysisValidationException(code,"Invalid policy scope, query or embedding"); }
}
