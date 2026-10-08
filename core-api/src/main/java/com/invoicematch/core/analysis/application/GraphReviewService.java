package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic human opinion + audit + actor-scoped response + resume intent. No SDK or broker I/O. */
@Service
public class GraphReviewService {
    private final GraphExecutionGuard guard;
    private final GraphStore store;
    private final GraphReviewValidator validator;
    private final AuthorizationService authorization;
    private final RequestIdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    public GraphReviewService(GraphExecutionGuard guard,GraphStore store,GraphReviewValidator validator,
            AuthorizationService authorization,RequestIdempotencyStore idempotency,AuditRecorder audit,ObjectMapper mapper,Clock clock) {
        this.guard=guard;this.store=store;this.validator=validator;this.authorization=authorization;
        this.idempotency=idempotency;this.audit=audit;this.mapper=mapper;this.clock=clock;
    }
    public record Command(String requestId,Long expectedCaseVersion,String interruptId,String checkpointHash,
            Integer reviewVersion,JsonNode confirmation,String reason) {}
    public record Saved(UUID graphExecutionId,UUID reviewId,String interruptId,int reviewVersion,UUID resumeEventId,
            String reviewStatus,String resumeStatus) {}
    @Transactional public CommandResult<Saved> confirm(UUID caseId,UUID id,Command command) {
        authorization.requireRole(Role.OPERATOR);authorization.requireCaseRead(caseId);validate(command);
        var run=guard.reviewRun(caseId,id);var actor=authorization.actor();
        String scope="graph:review",resource=caseId+":"+id;
        var body=mapper.valueToTree(command);((com.fasterxml.jackson.databind.node.ObjectNode)body).remove("requestId");
        String fingerprint=AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(body));
        var begin=idempotency.begin(scope,resource,actor.username(),command.requestId(),fingerprint);
        if(begin instanceof RequestIdempotencyStore.BeginResult.Replay replay)
            return new CommandResult<>(replay.response().status(),idempotency.decode(replay.response(),Saved.class));
        guard.enabled();
        if(!run.status().equals("WAITING_HUMAN") || run.caseVersion()!=command.expectedCaseVersion() || !guard.current(run))
            throw GraphExecutionService.conflict("GRAPH_REVIEW_CONFLICT");
        var wait=store.waiting(id).orElseThrow(()->GraphExecutionService.conflict("GRAPH_REVIEW_CONFLICT"));
        if(!wait.interruptId().equals(command.interruptId()) || !wait.checkpointHash().equals(command.checkpointHash())
                || wait.reviewVersion()!=command.reviewVersion())throw GraphExecutionService.conflict("GRAPH_REVIEW_CONFLICT");
        validator.validate(run,wait,command.confirmation(),store);
        UUID reviewId=UUID.randomUUID(),eventId=UUID.randomUUID();
        String confirmation=AnalysisCanonicalJson.canonicalize(command.confirmation());
        store.review(id,wait,reviewId,eventId,actor.username(),command.requestId(),confirmation,
                AnalysisCanonicalJson.sha256Hex(confirmation),command.reason());
        var event=mapper.createObjectNode().put("schemaVersion","graph-resume-request-v1").put("eventId",eventId.toString())
                .put("graphExecutionId",id.toString()).put("workflowVersion",com.invoicematch.core.analysis.domain.GraphRun.WORKFLOW)
                .put("contextHash",run.contextHash()).put("interruptId",wait.interruptId()).put("reviewId",reviewId.toString())
                .put("reviewVersion",wait.reviewVersion()).put("checkpointId",wait.checkpointId().toString()).put("checkpointHash",wait.checkpointHash());
        store.resumeEvent(eventId,id,reviewId,wait,AnalysisCanonicalJson.canonicalize(event));
        store.queueResume(id);
        audit.record(new AuditEvent(caseId,actor,AuditAction.AI_GRAPH_REVIEW_SAVED,AuditTargetType.CASE,caseId.toString(),
                run.caseVersion(),null,Map.of("graphExecutionId",id,"reviewId",reviewId,"interruptId",wait.interruptId(),
                    "resumeEventId",eventId,"checkpointHash",wait.checkpointHash()),command.requestId(),clock.instant()));
        var response=new Saved(id,reviewId,wait.interruptId(),wait.reviewVersion(),eventId,"SAVED","QUEUED");
        idempotency.recordResponse(scope,resource,actor.username(),command.requestId(),202,response);
        return new CommandResult<>(202,response);
    }
    private static void validate(Command c) {
        if(c==null || c.requestId()==null || c.requestId().isBlank() || c.requestId().length()>80 || c.requestId().chars().anyMatch(Character::isISOControl)
                || c.expectedCaseVersion()==null || c.expectedCaseVersion()<0 || c.interruptId()==null || !c.interruptId().matches("[0-9a-f]{32,64}")
                || !GraphPayloadValidator.hash(c.checkpointHash()) || c.reviewVersion()==null || c.reviewVersion()<1 || c.confirmation()==null
                || !c.confirmation().isObject() || c.reason()==null || c.reason().isBlank() || c.reason().length()>1000
                || c.reason().chars().anyMatch(Character::isISOControl))throw GraphReviewValidator.invalid();
        GraphReviewValidator.bounded(c.confirmation(),0,new int[]{0});
        if(AnalysisCanonicalJson.canonicalize(c.confirmation()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw GraphReviewValidator.invalid();
    }
}
