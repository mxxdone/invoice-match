package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.persistence.GraphDeliveryStore;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human graph reads. Each run is locked case -> graph before currentness, so the
 * read serializes with worker claim and human confirm/mapping writes, then may
 * confirm a changed shared input as STALE and cancel an unissued resume.
 * Checkpoint bodies, leases and SDK state never leave this projection.
 */
@Service
public class GraphQueryService {
    private final GraphExecutionGuard guard;
    private final GraphStore store;
    private final GraphDeliveryStore delivery;
    private final GraphProperties properties;
    private final ProposalSourceCatalog catalog;
    private final GraphReviewValidator reviewer;
    private final AuthorizationService authorization;
    private final ObjectMapper mapper;
    public GraphQueryService(GraphExecutionGuard guard,GraphStore store,GraphDeliveryStore delivery,
            GraphProperties properties,ProposalSourceCatalog catalog,GraphReviewValidator reviewer,
            AuthorizationService authorization,ObjectMapper mapper) {
        this.guard=guard;this.store=store;this.delivery=delivery;this.properties=properties;
        this.catalog=catalog;this.reviewer=reviewer;this.authorization=authorization;this.mapper=mapper;
    }
    private void readPermission(UUID caseId) {authorization.requireRole(Role.OPERATOR,Role.APPROVER);authorization.requireCaseRead(caseId);}
    private record Resolved(GraphRun run,boolean supported,boolean current) {}

    @Transactional
    public GraphViews.Page list(UUID caseId) {
        readPermission(caseId);
        var resolved=new ArrayList<Resolved>();
        for(var id:store.recent(caseId))resolved.add(resolveLocked(caseId,id));
        var history=new ArrayList<GraphViews.Summary>(resolved.size());
        for(var item:resolved)history.add(summary(item));
        return new GraphViews.Page(properties.enabled(),resolved.isEmpty()?null:view(resolved.getFirst()),history);
    }
    @Transactional
    public GraphViews.View view(UUID caseId,UUID id) {
        readPermission(caseId);
        return view(resolveLocked(caseId,id));
    }
    /** Lock case -> graph first, then confirm currentness against the latest committed run. */
    private Resolved resolveLocked(UUID caseId,UUID id) {
        var run=store.lock(id).filter(r->r.caseId().equals(caseId)).orElseThrow(()->new AnalysisRunNotFoundException(id));
        if(run.checkpointSchema()!=GraphRun.SCHEMA)return new Resolved(run,false,false);
        if(run.status().equals("STALE"))return new Resolved(run,true,false);
        boolean current=guard.current(run);
        if(!current) {
            store.terminal(run.id(),"STALE",null);
            delivery.cancel(run.id());
            return new Resolved(store.read(run.id()).orElse(run),true,false);
        }
        return new Resolved(run,true,current);
    }
    private GraphViews.View view(Resolved resolved) {
        var run=resolved.run();
        if(!resolved.supported())return new GraphViews.View(summary(resolved),null,null,null,List.of());
        GraphViews.Pending pending=null;
        if(run.status().equals("WAITING_HUMAN") && resolved.current()) {
            var wait=store.waiting(run.id()).orElseThrow(GraphReviewValidator::invalid);
            var frozen=reviewer.pending(run,wait,store);
            pending=new GraphViews.Pending(wait.interruptId(),wait.checkpointHash(),wait.reviewVersion(),
                frozen.documentStageRef(),frozen.mappingStageRef(),frozen.reasonCodes(),frozen.document(),frozen.mapping());
        }
        var review=store.review(run.id()).map(r->new GraphViews.Review(r.id(),r.actor(),r.reason(),
            parse(r.confirmation()),r.createdAt(),resumeStatus(run.status()))).orElse(null);
        var payload=run.status().equals("COMPLETED")?store.result(run.id()).map(r->parse(r.payload())).orElse(null):null;
        var sources=List.copyOf(catalog.sources(store.advisoryInput(run.id()),store.validationSteps(run.id())).values());
        return new GraphViews.View(summary(resolved),pending,review,payload,sources);
    }
    private GraphViews.Summary summary(Resolved resolved) {
        var run=resolved.run();
        var stages=store.stages(run.id()).stream().map(GraphStore.Stage::stage).filter(s->!s.startsWith("tool:")).toList();
        return new GraphViews.Summary(run.id(),run.status(),run.segment(),resolved.current(),resolved.supported(),
            run.caseVersion(),run.contextHash(),store.result(run.id()).map(GraphStore.Result::hash).orElse(null),
            run.startAttempts(),run.resumeAttempts(),run.reservedCalls(),run.reservedTokens(),run.toolCalls(),
            run.errorCode(),stages,store.predecessor(run.id()).orElse(null),run.createdAt());
    }
    private static String resumeStatus(String status) {
        return switch(status) {
            case "STALE" -> "CANCELLED";
            case "QUEUED","RUNNING","COMPLETED","FAILED" -> status;
            default -> "QUEUED";
        };
    }
    private JsonNode parse(String value) {
        try {return mapper.readTree(value);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Invalid stored graph JSON");}
    }
}
