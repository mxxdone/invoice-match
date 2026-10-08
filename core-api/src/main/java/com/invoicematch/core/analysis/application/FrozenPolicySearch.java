package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.invoicematch.core.analysis.application.PolicySearchService.invalid;
import static com.invoicematch.core.analysis.application.PolicySearchService.validateEmbedding;
import org.springframework.stereotype.Component;

/** Bounded ranking within frozen policy scope; execution fencing and ledger writes belong to callers. */
@Component
class FrozenPolicySearch {
    private final PolicyCatalogStore policies;
    private final ProposalSourceCatalog sources;
    private final ObjectMapper mapper;

    FrozenPolicySearch(PolicyCatalogStore policies, ProposalSourceCatalog sources, ObjectMapper mapper) {
        this.policies = policies;
        this.sources = sources;
        this.mapper = mapper;
    }

    static void validateRequest(PolicySearchService.Request request) {
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
    }

    /** Caller owns fencing, budget admission and immutable storage. */
    JsonNode readFrozen(ProposalRun run,PolicySearchService.Request request) {
        validateRequest(request);
        boolean vector=!request.mode().equals("LEXICAL");
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
}
