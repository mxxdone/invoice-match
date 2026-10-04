package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Graph stages reuse Core advisory validation; no SDK or external I/O in transactions. */
@Service
public class GraphStageService {
    private final GraphExecutionService execution;
    private final GraphStore store;
    private final ProposalStageValidator validator;
    private final ProposalAssembler assembler;
    private final ProposalToolService tools;
    private final PolicySearchService policies;
    private final ObjectMapper mapper;
    private final com.invoicematch.core.document.persistence.DocumentStore documents;
    public GraphStageService(GraphExecutionService execution,GraphStore store,ProposalStageValidator validator,
            ProposalAssembler assembler,ProposalToolService tools,PolicySearchService policies,ObjectMapper mapper,com.invoicematch.core.document.persistence.DocumentStore documents) {
        this.execution=execution;this.store=store;this.validator=validator;this.assembler=assembler;this.tools=tools;this.policies=policies;this.mapper=mapper;this.documents=documents;
    }
    public record Stage(UUID ref,String stage,String hash,JsonNode payload) {}
    @Transactional public List<Stage> read(UUID id,String hash,UUID token) {
        active(id,hash,token);return store.stages(id).stream().map(s->new Stage(s.id(),s.stage(),s.hash(),parse(s.payload()))).toList();
    }
    @Transactional public Stage save(UUID id,String hash,UUID token,String stage,JsonNode payload) {
        active(id,hash,token);var run=store.advisoryInput(id);var steps=store.validationSteps(id);
        validator.validate(stage,payload,run,steps);
        if(stage.equals("execution") && payload.path("costCeiling").decimalValue().compareTo(store.costCeiling(id))>0)
            throw GraphExecutionService.conflict("AI_CONFIGURATION");
        var existing=store.stages(id).stream().filter(s->s.stage().equals(stage)).findFirst();
        if(existing.isPresent())return replay(existing.get(),payload);
        int calls=payload.path("calls").size(),tokens=usage(payload);
        for(var s:steps)if(!s.stage().startsWith("tool:")) {var n=parse(s.payload());calls+=n.path("calls").size();tokens+=usage(n);}
        if(calls>run.reservedCalls() || tokens>run.reservedTokens())throw ProposalSourceCatalog.invalid();
        return persist(id,token,stage,payload);
    }
    @Transactional public boolean reserve(UUID id,String hash,UUID token,UUID requestId,int tokens) {
        active(id,hash,token);
        if(requestId==null || tokens<1 || tokens>40000)throw GraphPayloadValidator.invalid();
        var existing=store.reservation(id,requestId);
        if(existing.isPresent()) {if(existing.get()!=tokens)throw GraphExecutionService.conflict("CALL_CONFLICT");return false;}
        var run=store.advisoryInput(id);var plan=store.stages(id).stream().filter(s->s.stage().equals("execution")).findFirst().orElseThrow(ProposalSourceCatalog::invalid);
        var config=parse(plan.payload());validator.validate("execution",config,run,store.validationSteps(id));
        BigDecimal rate=config.path("inputPricePerMillion").decimalValue().max(config.path("outputPricePerMillion").decimalValue())
            .max(config.path("embedding").path("pricePerMillion").decimalValue());
        var cost=rate.multiply(BigDecimal.valueOf(tokens)).divide(BigDecimal.valueOf(1000000));
        if(run.reservedCalls()>=5 || run.reservedTokens()+tokens>40000 || store.reservedCost(id).add(cost).compareTo(
            config.path("costCeiling").decimalValue().min(store.costCeiling(id)))>0)throw GraphExecutionService.conflict("AI_BUDGET_EXHAUSTED");
        store.reserve(id,requestId,tokens,cost,token);return true;
    }
    @Transactional public JsonNode tool(UUID id,String hash,UUID token,ProposalToolService.Request request) {
        active(id,hash,token);if(request==null || request.requestId()==null)throw GraphPayloadValidator.invalid();
        return toolResult(id,token,"tool:"+request.requestId(),mapper.valueToTree(request),()->tools.readFrozen(store.advisoryInput(id),request));
    }
    @Transactional public JsonNode policy(UUID id,String hash,UUID token,PolicySearchService.Request request) {
        active(id,hash,token);if(request==null || request.requestId()==null)throw GraphPayloadValidator.invalid();
        return toolResult(id,token,"tool:"+request.requestId(),mapper.valueToTree(request),()->policies.readFrozen(store.advisoryInput(id),request));
    }
    private JsonNode toolResult(UUID id,UUID token,String stage,JsonNode arguments,java.util.function.Supplier<JsonNode> read) {
        var existing=store.stages(id).stream().filter(s->s.stage().equals(stage)).findFirst();
        if(existing.isPresent()) {
            var saved=parse(existing.get().payload());
            if(!AnalysisCanonicalJson.canonicalize(saved.path("request")).equals(AnalysisCanonicalJson.canonicalize(arguments)))
                throw GraphExecutionService.conflict("TOOL_CONFLICT");return saved;
        }
        if(store.advisoryInput(id).toolCalls()>=8)throw GraphExecutionService.conflict("AI_BUDGET_EXHAUSTED");
        var result=read.get();persist(id,token,stage,result);return result;
    }
    @Transactional public GraphStore.Result complete(UUID id,String hash,UUID token) {
        var run=execution.lock(id,hash);execution.active(run,token);
        var input=store.advisoryInput(id);var steps=store.validationSteps(id);
        if(run.segment().equals("START") && !humanReasons(steps).isEmpty())throw GraphExecutionService.conflict("GRAPH_HUMAN_REQUIRED");
        var assembled=assembler.assemble(input,steps);
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)parse(assembled.canonical());
        result.put("schemaVersion","advisory-proposal-v2").put("graphVersion",com.invoicematch.core.analysis.domain.GraphRun.GRAPH);
        if(run.segment().equals("RESUME")) {
            var review=store.review(id).orElseThrow(()->GraphExecutionService.conflict("GRAPH_REVIEW_MISSING"));
            var checkpoint=store.latest(id).orElseThrow(()->GraphExecutionService.conflict("GRAPH_CHECKPOINT_MISSING"));
            var values=new GraphPayloadValidator(mapper).decode(parse(checkpoint.envelope()).path("body"),0,false).path("channel_values");
            if(!store.reviewConsumed(id,review.id()) || !values.path("reviewRef").asText().equals(review.id().toString())
                || !values.has("resolutionStageRef"))throw GraphExecutionService.conflict("GRAPH_RESUME_NOT_READY");
            result.set("humanReview",mapper.createObjectNode().put("reviewId",review.id().toString()).put("confirmationHash",review.hash())
                .set("confirmation",parse(review.confirmation())));
        }
        String canonical=AnalysisCanonicalJson.canonicalize(result);
        bounded(canonical);store.complete(id,canonical,AnalysisCanonicalJson.sha256Hex(canonical),token);
        return store.result(id).orElseThrow();
    }
    static Set<String> humanReasons(List<ProposalStore.Step> steps) {
        var result=new java.util.TreeSet<String>();var mapper=new ObjectMapper();
        for(var step:steps)try {
            var payload=mapper.readTree(step.payload()).path("result");
            if(step.stage().equals("document")) {
                if(payload.path("fields").isEmpty() && payload.path("lines").isEmpty())result.add("DOCUMENT_REVIEW_REQUIRED");
                for(var warning:payload.path("warnings"))if(Set.of("DOCUMENT_CONFLICT","AMBIGUOUS_LAYOUT").contains(warning.asText()))result.add("DOCUMENT_REVIEW_REQUIRED");
            }
            if(step.stage().equals("mapping"))for(var line:payload.path("lines")) {
                if(line.path("candidates").isEmpty())result.add("NO_ITEM_CANDIDATE");
                if(line.path("candidates").size()>1)result.add("AMBIGUOUS_ITEM");
            }
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw ProposalSourceCatalog.invalid();}
        return result;
    }
    @Transactional
    public com.invoicematch.core.document.application.DocumentOriginalRequest authorizeSource(UUID id,String hash,UUID token,UUID documentId) {
        active(id,hash,token);var run=store.advisoryInput(id);
        var context=parse(run.context());
        var frozen=java.util.stream.StreamSupport.stream(context.path("evidenceBundle").path("documents").spliterator(),false)
            .filter(d->d.path("documentId").asText().equals(documentId.toString())).findFirst().orElseThrow(ProposalSourceCatalog::invalid);
        var registered=documents.document(run.caseId(),documentId).orElseThrow(ProposalSourceCatalog::invalid);var u=registered.upload();
        if(!u.draftRevisionId().toString().equals(frozen.path("sourceDraftRevisionId").asText()) || !u.fileName().equals(frozen.path("fileName").asText())
            || !u.mediaType().equals(frozen.path("mediaType").asText()) || u.sizeBytes()!=frozen.path("sizeBytes").asLong() || !u.checksum().equals(frozen.path("checksum").asText())) throw ProposalSourceCatalog.invalid();
        return new com.invoicematch.core.document.application.DocumentOriginalRequest(registered.objectKey(),u.mediaType(),u.sizeBytes(),u.checksum());
    }
    private void active(UUID id,String hash,UUID token) {execution.active(execution.lock(id,hash),token);}
    private Stage persist(UUID id,UUID token,String stage,JsonNode payload) {
        String canonical=AnalysisCanonicalJson.canonicalize(payload);bounded(canonical);
        String hash=AnalysisCanonicalJson.sha256Hex(canonical);UUID ref=store.stage(id,stage,canonical,hash,token);
        return new Stage(ref,stage,hash,parse(canonical));
    }
    private void bounded(String value) {if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>120000 || store.jsonBytes(value)>131072)throw ProposalSourceCatalog.invalid();}
    private Stage replay(GraphStore.Stage saved,JsonNode payload) {
        if(!saved.hash().equals(AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(payload))))throw GraphExecutionService.conflict("STEP_CONFLICT");
        return new Stage(saved.id(),saved.stage(),saved.hash(),parse(saved.payload()));
    }
    private JsonNode parse(String value) {try{return mapper.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ProposalSourceCatalog.invalid();}}
    private static int usage(JsonNode n) {int tokens=0;for(var c:n.path("calls"))tokens+=c.path("inputTokens").asInt()+c.path("outputTokens").asInt();return tokens;}
}
