package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.persistence.GraphDeliveryStore;
import com.invoicematch.core.analysis.persistence.GraphStore;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short durable transactions; no SDK, Rabbit or provider I/O. */
@Service
public class GraphDeliveryService {
    private final GraphExecutionGuard guard;
    private final GraphStore graphs;
    private final GraphDeliveryStore store;
    private final GraphProperties properties;
    private final ObjectMapper mapper;
    public GraphDeliveryService(GraphExecutionGuard guard,GraphStore graphs,GraphDeliveryStore store,GraphProperties properties,ObjectMapper mapper) {
        this.guard=guard;this.graphs=graphs;this.store=store;this.properties=properties;this.mapper=mapper;
    }
    private static final Set<String> TRANSIENT=Set.of("AI_RATE_LIMIT","AI_TIMEOUT","AI_FAILED","OCR_RATE_LIMIT","OCR_TIMEOUT","OCR_FAILED","CORE_UNAVAILABLE","CORE_TRANSIENT");
    private static final Set<String> PERMANENT=Set.of("GRAPH_SDK_FAILED","GRAPH_LIMIT","GRAPH_REPEATED_INTERRUPT","AI_CONFIGURATION","AI_SCHEMA_INVALID","AI_BUDGET_EXHAUSTED","AI_INPUT_LIMIT","AI_TOOL_DENIED","OCR_CONFIGURATION","OCR_LIMIT","OCR_INVALID_RESPONSE","SOURCE_MISMATCH","INVALID_PROTOCOL");
    public record Proof(String disposition,String runStatus) {}
    public record Resume(UUID reviewRef,UUID checkpointId,String checkpointHash,String interruptId,int reviewVersion,JsonNode confirmation) {}
    @Transactional public GraphExecutionService.Claim claimResume(UUID id,String hash,JsonNode identity) {
        var run=guard.lock(id,hash);var event=identity(id,identity);
        if(run.terminal())return claim("ALREADY_FINISHED");
        if(!guard.current(run)) {graphs.terminal(id,"STALE",null);store.cancel(id);return claim("STALE");}
        if(!run.segment().equals("RESUME"))throw GraphExecutionService.conflict("GRAPH_RESUME_NOT_READY");
        if(run.leaseActive() || !store.due(id))return new GraphExecutionService.Claim("BUSY",null,run.leaseUntil(),null,null);
        if(run.attempts()>=3) {graphs.terminal(id,"FAILED","LEASE_EXPIRED");store.cancel(id);return claim("ALREADY_FINISHED");}
        UUID token=UUID.randomUUID();var until=graphs.claim(id,token,properties.leaseDuration());
        store.consume(event,GraphPayloadValidator.uuid(parse(event.payload()).path("reviewId").asText()),token);
        return new GraphExecutionService.Claim("CLAIMED",token,until,parse(run.context()),null);
    }
    @Transactional public Resume resume(UUID id,String hash,UUID token,JsonNode identity) {
        var run=guard.lock(id,hash);identity(id,identity);guard.active(run,token);
        var review=graphs.review(id).orElseThrow(()->GraphExecutionService.conflict("GRAPH_REVIEW_MISSING"));
        if(!run.segment().equals("RESUME") || !store.consumed(id,review.id()))throw GraphExecutionService.conflict("GRAPH_RESUME_NOT_READY");
        var wait=graphs.waiting(id).orElseThrow();
        return new Resume(review.id(),wait.checkpointId(),wait.checkpointHash(),wait.interruptId(),wait.reviewVersion(),parse(review.confirmation()));
    }
    private GraphDeliveryStore.Event identity(UUID id,JsonNode identity) {
        var event=store.event(id,"RESUME").orElseThrow(()->GraphExecutionService.conflict("GRAPH_REVIEW_MISSING"));
        if(!parse(event.payload()).equals(identity))throw GraphExecutionService.conflict("GRAPH_RESUME_IDENTITY_MISMATCH");
        return event;
    }
    @Transactional public Proof defer(UUID id,String hash,String segment,JsonNode identity) {
        var run=guard.lock(id,hash);var event=event(id,segment,identity);
        if(run.terminal())return new Proof("CHECKPOINTED",run.status());
        if(!guard.current(run)) {graphs.terminal(id,"STALE",null);store.cancel(id);return new Proof("CHECKPOINTED","STALE");}
        if(!run.segment().equals(segment) || run.status().equals("WAITING_HUMAN"))return new Proof("CHECKPOINTED",run.status());
        store.defer(event,"defer:"+(run.token()==null?"queued:"+run.attempts():run.token()),Duration.ofSeconds(1));
        return new Proof("CHECKPOINTED",run.status());
    }
    @Transactional public Proof failure(UUID id,String hash,UUID token,String code) {
        var run=guard.lock(id,hash);var recorded=store.failure(id,token);
        if(recorded.isPresent() || run.terminal())return new Proof("CHECKPOINTED",run.status());
        guard.active(run,token);
        if(!TRANSIENT.contains(code) && !PERMANENT.contains(code))throw GraphPayloadValidator.invalid();
        boolean retry=TRANSIENT.contains(code) && run.attempts()<3;
        Duration delay=Duration.ofSeconds(5L<<(run.attempts()-1));String status=retry?"QUEUED":"FAILED";
        store.failure(id,token,run.segment(),code,status,delay);
        if(retry)store.defer(event(id,run.segment(),null),"failure:"+token,delay);else store.cancel(id);
        return new Proof("CHECKPOINTED",status);
    }
    private GraphDeliveryStore.Event event(UUID id,String segment,JsonNode identity) {
        if(!Set.of("START","RESUME").contains(segment))throw GraphPayloadValidator.invalid();
        if(segment.equals("RESUME") && identity!=null)return identity(id,identity);
        return store.event(id,segment).orElseThrow(()->GraphExecutionService.conflict("GRAPH_EVENT_MISSING"));
    }
    @Transactional public Optional<GraphDeliveryStore.Dispatch> claimDispatch() {
        guard.enabled();
        // One case per transaction prevents carrying scope/case locks into another case.
        for(var id:store.candidates(1)) {
            var run=graphs.lock(id).orElseThrow();
            if(run.terminal() || run.status().equals("WAITING_HUMAN")) {store.cancel(id);continue;}
            if(!guard.current(run)) {graphs.terminal(id,"STALE",null);store.cancel(id);continue;}
            store.cancelOther(id,run.segment());
            if(run.leaseActive()) {store.postpone(id,run.segment());continue;}
            var dispatch=store.claim(id,run.segment(),Duration.ofSeconds(60));
            if(dispatch.isPresent())return dispatch;
        }
        return Optional.empty();
    }
    @Transactional public boolean settle(GraphDeliveryStore.Dispatch dispatch,boolean published) {
        graphs.lock(dispatch.runId()).orElseThrow();return store.settle(dispatch,published,Duration.ofSeconds(30));
    }
    private static GraphExecutionService.Claim claim(String disposition) {return new GraphExecutionService.Claim(disposition,null,null,null,null);}
    private JsonNode parse(String value) {try{return mapper.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid stored graph intent");}}
}
