package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fenced v1 tool calls; owns budget admission, replay and immutable result storage. */
@Service
public class ProposalToolService {
    private final ProposalExecutionService execution;
    private final ProposalStore store;
    private final FrozenProposalTools frozen;
    private final ProposalSourceCatalog sources;
    private final ObjectMapper mapper;
    public ProposalToolService(ProposalExecutionService execution,ProposalStore store,FrozenProposalTools frozen,
            ProposalSourceCatalog sources,ObjectMapper mapper) {
        this.execution=execution;this.store=store;this.frozen=frozen;this.sources=sources;this.mapper=mapper;
    }
    public record Request(UUID requestId,String tool,String query,int limit) {}
    @Transactional
    public JsonNode call(UUID runId,String contextHash,UUID token,Request request) {
        FrozenProposalTools.validateRequest(request);
        var run=execution.active(runId,contextHash,token);
        String stage="tool:"+request.requestId();
        var arguments=mapper.valueToTree(request);
        var existing=store.steps(runId).stream().filter(s->s.stage().equals(stage)).findFirst();
        if(existing.isPresent()) {
            var frozen=sources.parse(existing.get().payload());
            if(!frozen.path("request").equals(arguments)) throw ProposalExecutionService.conflict("TOOL_CONFLICT");
            return frozen;
        }
        if(run.toolCalls()>=ProposalRun.MAX_TOOLS) throw ProposalExecutionService.conflict("AI_BUDGET_EXHAUSTED");
        var output=frozen.readFrozen(run,request);
        String canonical=AnalysisCanonicalJson.canonicalize(output);
        store.countTool(runId);
        store.step(runId,stage,canonical,AnalysisCanonicalJson.sha256Hex(canonical));
        return output;
    }
}
