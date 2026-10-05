package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.util.List;
import org.springframework.stereotype.Component;

/** The single graph completion boundary: v1 assembly plus graph identity and exact human review. */
@Component
public class GraphProposalAssembler {
    private final ProposalAssembler assembler;
    private final GraphStore store;
    private final GraphPayloadValidator wire;
    private final ObjectMapper mapper;
    public GraphProposalAssembler(ProposalAssembler assembler,GraphStore store,GraphPayloadValidator wire,ObjectMapper mapper) {
        this.assembler=assembler;this.store=store;this.wire=wire;this.mapper=mapper;
    }
    public record Assembled(String canonical,String hash) {}
    public Assembled assemble(GraphRun run,ProposalRun input,List<ProposalStore.Step> steps) {
        var assembled=assembler.assemble(input,steps);
        var result=(ObjectNode)parse(assembled.canonical());
        result.put("schemaVersion","advisory-proposal-v2").put("graphVersion",GraphRun.GRAPH);
        if(run.segment().equals("RESUME")) {
            var review=store.review(run.id()).orElseThrow(()->GraphExecutionService.conflict("GRAPH_REVIEW_MISSING"));
            var checkpoint=store.latest(run.id()).orElseThrow(()->GraphExecutionService.conflict("GRAPH_CHECKPOINT_MISSING"));
            var values=wire.decode(parse(checkpoint.envelope()).path("body"),0,false).path("channel_values");
            if(!store.reviewConsumed(run.id(),review.id()) || !values.path("reviewRef").asText().equals(review.id().toString())
                || !values.has("resolutionStageRef"))throw GraphExecutionService.conflict("GRAPH_RESUME_NOT_READY");
            result.set("humanReview",mapper.createObjectNode().put("reviewId",review.id().toString()).put("confirmationHash",review.hash())
                .set("confirmation",parse(review.confirmation())));
        }
        String canonical=AnalysisCanonicalJson.canonicalize(result);
        return new Assembled(canonical,AnalysisCanonicalJson.sha256Hex(canonical));
    }
    private JsonNode parse(String value) {
        try {return mapper.readTree(value);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Invalid stored graph JSON");}
    }
}
