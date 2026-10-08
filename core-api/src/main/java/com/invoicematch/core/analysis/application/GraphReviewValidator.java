package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.GraphCheckpointStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Confirms opinions about frozen candidates; never changes document values or item mappings. */
@Component
public class GraphReviewValidator {
    private final GraphCheckpointStore checkpoints;
    private final ProposalStageValidator stages;
    private final GraphPayloadValidator wire;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public GraphReviewValidator(GraphCheckpointStore checkpoints,ProposalStageValidator stages,GraphPayloadValidator wire,com.fasterxml.jackson.databind.ObjectMapper mapper) {this.checkpoints=checkpoints;this.stages=stages;this.wire=wire;this.mapper=mapper;}
    /** Frozen pending projection for reads; the same stored-stage/interrupt checks the confirm path uses. */
    public record Pending(UUID documentStageRef,UUID mappingStageRef,List<String> reasonCodes,JsonNode document,JsonNode mapping) {}
    public Pending pending(GraphRun run,GraphStore.Waiting wait,GraphStore store) {
        var saved=store.stages(run.id());var input=store.advisoryInput(run.id());var steps=store.validationSteps(run.id());
        var document=stage(saved,"document");var mapping=stage(saved,"mapping");
        // Revalidate the immutable outputs against their frozen original sources and server candidates.
        stages.validate("document",document.payload(),input,steps);stages.validate("mapping",mapping.payload(),input,steps);
        var write=checkpoints.write(run.id(),wait.checkpointId(),wait.taskId(),-3,wait.writeVersion()).orElseThrow(GraphReviewValidator::invalid);
        var request=wire.decode(parse(write.payload()),0,true).path(0).path("value");
        if(!request.path("documentStageRef").asText().equals(document.ref().toString())
                || !request.path("mappingStageRef").asText().equals(mapping.ref().toString()))throw invalid();
        var reasons=GraphStageService.humanReasons(steps);var actual=new HashSet<String>();request.path("reasonCodes").forEach(v->actual.add(v.asText()));
        if(!actual.equals(reasons) || reasons.isEmpty())throw invalid();
        return new Pending(document.ref(),mapping.ref(),List.copyOf(reasons),document.payload().path("result"),mapping.payload().path("result"));
    }
    public void validate(GraphRun run,GraphStore.Waiting wait,JsonNode confirmation,GraphStore store) {
        var pending=pending(run,wait,store);
        GraphPayloadValidator.keys(confirmation,"documentStageRef","mappingStageRef","documentDecision","itemDecisions");
        if(!confirmation.path("documentStageRef").asText().equals(pending.documentStageRef().toString())
                || !confirmation.path("mappingStageRef").asText().equals(pending.mappingStageRef().toString()))throw invalid();
        String decision=GraphPayloadValidator.text(confirmation,"documentDecision");
        if(!(pending.reasonCodes().contains("DOCUMENT_REVIEW_REQUIRED")?Set.of("CONFIRMED","NEEDS_CORRECTION"):Set.of("NOT_REQUIRED")).contains(decision))throw invalid();
        var expected=new HashMap<Integer,JsonNode>();
        for(var line:pending.mapping().path("lines"))if(line.path("candidates").size()!=1)expected.put(line.path("lineNumber").asInt(),line);
        var choices=confirmation.path("itemDecisions");
        if(!choices.isArray() || choices.size()!=expected.size() || choices.size()>100)throw invalid();
        var seen=new HashSet<Integer>();
        for(var choice:choices) {
            GraphPayloadValidator.keys(choice,"lineNumber","source","itemId","purchaseOrderLineId");
            if(!choice.path("lineNumber").isInt())throw invalid();int number=choice.path("lineNumber").asInt();
            var line=expected.get(number);if(line==null || !seen.add(number) || !line.path("source").equals(choice.path("source")))throw invalid();
            // A null pair records that the person could not resolve the candidate. It is not a new mapping.
            if(choice.path("itemId").isNull() && choice.path("purchaseOrderLineId").isNull())continue;
            if(!choice.path("itemId").isTextual() || !choice.path("purchaseOrderLineId").isTextual())throw invalid();
            boolean candidate=false;
            for(var item:line.path("candidates"))if(item.path("itemId").equals(choice.path("itemId"))
                    && item.path("purchaseOrderLineId").equals(choice.path("purchaseOrderLineId")))candidate=true;
            if(!candidate)throw invalid();
        }
        if(AnalysisCanonicalJson.canonicalize(confirmation).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw invalid();
    }
    private record Stage(java.util.UUID ref,JsonNode payload) {}
    private Stage stage(List<GraphStore.Stage> saved,String name) {
        var stage=saved.stream().filter(s->s.stage().equals(name)).findFirst().orElseThrow(GraphReviewValidator::invalid);
        var payload=parse(stage.payload());if(!stage.hash().equals(AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(payload))))throw invalid();
        return new Stage(stage.id(),payload);
    }
    static void bounded(JsonNode value,int depth,int[] count) {
        if(value==null || depth>8 || ++count[0]>10000 || (value.isTextual() && value.textValue().length()>1024))throw invalid();
        if(value.isContainerNode())value.forEach(child->bounded(child,depth+1,count));
    }
    private JsonNode parse(String value) {try{return mapper.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw invalid();}}
    static AnalysisValidationException invalid() {return new AnalysisValidationException("GRAPH_REVIEW_INVALID","Review must refer to the pending original sources and candidates");}
}
