package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** No SDK, broker, model or external I/O is allowed within these transactions. */
@Service
@EnableConfigurationProperties(GraphProperties.class)
public class GraphExecutionService {
    private final GraphStore store;
    private final ProposalStore inputs;
    private final ProposalContextFactory contexts;
    private final PolicyCatalogStore policies;
    private final GraphProperties properties;
    private final GraphPayloadValidator validator;
    private final ObjectMapper mapper;
    private final AuthorizationService authorization;
    private final RequestIdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final Clock clock;
    private final com.invoicematch.core.analysis.persistence.GraphDeliveryStore delivery;
    public GraphExecutionService(GraphStore store, ProposalStore inputs, ProposalContextFactory contexts,
            PolicyCatalogStore policies, GraphProperties properties, GraphPayloadValidator validator,
            ObjectMapper mapper, AuthorizationService authorization, RequestIdempotencyStore idempotency,
            AuditRecorder audit, Clock clock,com.invoicematch.core.analysis.persistence.GraphDeliveryStore delivery) {
        this.store=store;this.inputs=inputs;this.contexts=contexts;this.policies=policies;this.properties=properties;
        this.validator=validator;this.mapper=mapper;this.authorization=authorization;this.idempotency=idempotency;this.audit=audit;this.clock=clock;this.delivery=delivery;
    }
    public record Reserved(UUID id, String status, String contextHash) {}
    public record Waiting(String interruptId, UUID checkpointId, String checkpointHash, UUID taskId,
            int writeVersion, String writeHash, int reviewVersion) {}
    public record Claim(String disposition, UUID token, Instant leaseUntil, JsonNode context, Waiting waiting) {}
    public record Stored(String disposition, String hash) {}
    public record WriteView(UUID taskId, int index, int version, String previousHash, String hash, String channel, String taskPath, JsonNode payload) {}
    public record CheckpointView(UUID id, UUID parentId, String hash, JsonNode envelope, List<WriteView> writes) {}

    /** Reserve frozen graph input and its immutable start delivery atomically. */
    @Transactional
    public CommandResult<Reserved> reserve(UUID caseId, ProposalService.ReserveCommand command) {
        authorization.requireRole(Role.OPERATOR);
        authorization.requireCaseRead(caseId);
        if(command==null || command.requestId()==null || command.requestId().isBlank() || command.requestId().length()>80
                || command.expectedCaseVersion()==null || command.expectedCaseVersion()<0)throw GraphPayloadValidator.invalid();
        var state=inputs.lockCase(caseId).orElseThrow(()->new InvoiceCaseNotFoundException(caseId));
        String scope="graph:reserve",resource=caseId.toString();var actor=authorization.actor();
        String fingerprint=AnalysisCanonicalJson.sha256Hex(mapper.createObjectNode().put("caseVersion",command.expectedCaseVersion()).toString());
        var begin=idempotency.begin(scope,resource,actor.username(),command.requestId(),fingerprint);
        if(begin instanceof RequestIdempotencyStore.BeginResult.Replay replay)
            return new CommandResult<>(replay.response().status(),idempotency.decode(replay.response(),Reserved.class));
        enabled();
        if(state.version()!=command.expectedCaseVersion() || !java.util.Set.of("SUBMITTED","REVIEW_PENDING").contains(state.status()))throw conflict("GRAPH_INPUT_CONFLICT");
        var source=inputs.source(caseId).orElseThrow(()->conflict("GRAPH_INPUT_CONFLICT"));
        var context=contexts.create(caseId,source).put("schemaVersion","ai-graph-context-v1")
                .put("caseVersion",state.version()).put("workflowVersion",GraphRun.WORKFLOW)
                .put("graphVersion",GraphRun.GRAPH).put("serializerVersion",GraphRun.SERIALIZER).put("checkpointSchema",GraphRun.SCHEMA);
        String canonical=AnalysisCanonicalJson.canonicalize(context),hash=AnalysisCanonicalJson.sha256Hex(canonical);
        var existing=store.existing(caseId,hash);Reserved response;
        if(existing.isPresent())response=new Reserved(existing.get().id(),existing.get().status(),hash);
        else {
            UUID id=UUID.randomUUID();store.insert(id,caseId,state.version(),source,canonical,hash,properties.costCeiling());
            delivery.start(id,AnalysisCanonicalJson.canonicalize(mapper.createObjectNode().put("schemaVersion","graph-request-v1")
                .put("eventId",id.toString()).put("graphExecutionId",id.toString()).put("workflowVersion",GraphRun.WORKFLOW).put("contextHash",hash)));
            response=new Reserved(id,"QUEUED",hash);
            audit.record(new AuditEvent(caseId,actor,AuditAction.AI_ANALYSIS_RESERVED,AuditTargetType.CASE,caseId.toString(),
                    state.version(),null,Map.of("graphExecutionId",id,"workflowVersion",GraphRun.WORKFLOW,"contextHash",hash),command.requestId(),clock.instant()));
        }
        idempotency.recordResponse(scope,resource,actor.username(),command.requestId(),201,response);
        return CommandResult.created(response);
    }
    @Transactional
    public Claim claim(UUID id,String hash) {
        var run=lock(id,hash);
        if(run.terminal())return new Claim("ALREADY_FINISHED",null,null,null,null);
        if(!current(run)) {store.terminal(id,"STALE",null);return new Claim("STALE",null,null,null,null);}
        if(run.status().equals("WAITING_HUMAN"))return new Claim("WAITING_HUMAN",null,null,null,waiting(store.waiting(id).orElseThrow()));
        // Resume ownership is granted only by the exact review/checkpoint claim.
        if(run.segment().equals("RESUME"))return new Claim("ALREADY_FINISHED",null,null,null,null);
        if(run.leaseActive() || !delivery.due(id))return new Claim("BUSY",null,run.leaseUntil(),null,null);
        if(run.attempts()>=3) {store.terminal(id,"FAILED","LEASE_EXPIRED");return new Claim("ALREADY_FINISHED",null,null,null,null);}
        UUID token=UUID.randomUUID();Instant until=store.claim(id,token,properties.leaseDuration());
        return new Claim("CLAIMED",token,until,parse(run.context()),null);
    }
    @Transactional public Instant heartbeat(UUID id,String hash,UUID token) {
        active(lock(id,hash),token);return store.heartbeat(id,token,properties.leaseDuration());
    }
    @Transactional
    public Stored checkpoint(UUID id,String hash,UUID token,GraphCommands.Checkpoint command) {
        var run=lock(id,hash);active(run,token);validator.checkpoint(run,command);
        references(id,validator.decode(command.body(),0,false).path("channel_values"));
        var envelope=mapper.valueToTree(command);String canonical=bounded(envelope),payloadHash=AnalysisCanonicalJson.sha256Hex(canonical);
        var existing=store.checkpoint(id,command.checkpointId());
        if(existing.isPresent()) {
            if(!existing.get().hash().equals(payloadHash))throw conflict("GRAPH_CHECKPOINT_CONFLICT");
            return new Stored("REPLAYED",payloadHash);
        }
        var latest=store.latest(id);
        if(!java.util.Objects.equals(command.parentId(),latest.map(GraphStore.Checkpoint::id).orElse(null)))throw conflict("GRAPH_PARENT_CONFLICT");
        admit(run,store.jsonBytes(canonical),1,0);
        store.checkpoint(id,command.checkpointId(),command.parentId(),canonical,payloadHash,token);
        return new Stored("ACCEPTED",payloadHash);
    }
    @Transactional
    public List<Stored> writes(UUID id,String hash,UUID token,List<GraphCommands.Write> commands) {
        var run=lock(id,hash);active(run,token);
        if(commands==null || commands.isEmpty() || commands.size()>512)throw GraphPayloadValidator.invalid();
        bounded(mapper.valueToTree(commands));
        var result=new java.util.ArrayList<Stored>();
        for(var command:commands) {
            validator.write(run,command);
            var decoded=validator.decode(command.payload(),0,true);
            if(command.channel().endsWith("StageRef"))reference(id,command.channel(),decoded.asText());
            else if(command.channel().equals("reviewRef"))reviewReference(id,decoded);
            else references(id,decoded);
            if(command.channel().equals("__resume__")) {
                var wait=store.waiting(id).orElseThrow(()->conflict("GRAPH_RESUME_NOT_READY"));
                if(!run.segment().equals("RESUME") || !command.checkpointId().equals(wait.checkpointId()))throw conflict("GRAPH_RESUME_IDENTITY_MISMATCH");
                var reply=decoded.isObject()?decoded.path(wait.interruptId()):decoded.path(0);
                GraphPayloadValidator.keys(reply,"reviewRef");reviewReference(id,reply.get("reviewRef"));
            }
            if(command.channel().equals("__interrupt__") && !store.stages(id).isEmpty()) {
                var request=decoded.path(0).path("value");
                if(!request.has("documentStageRef") || !request.has("mappingStageRef"))throw conflict("GRAPH_STAGE_MISMATCH");
                var actual=new java.util.TreeSet<String>();request.path("reasonCodes").forEach(v->actual.add(v.asText()));
                if(!actual.equals(GraphStageService.humanReasons(store.validationSteps(id))))throw conflict("GRAPH_WAIT_CONFLICT");
            }
            if(store.checkpoint(id,command.checkpointId()).isEmpty())throw conflict("GRAPH_CHECKPOINT_MISSING");
            String canonical=bounded(command.payload());
            String payloadHash=AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(mapper.valueToTree(command)));
            var existing=store.write(id,command.checkpointId(),command.taskId(),command.index(),command.version());
            if(existing.isPresent()) {
                if(!existing.get().hash().equals(payloadHash))throw conflict("GRAPH_WRITE_CONFLICT");
                result.add(new Stored("REPLAYED",payloadHash));continue;
            }
            var previous=store.writes(id,command.checkpointId()).stream()
                    .filter(w->w.taskId().equals(command.taskId()) && w.index()==command.index()).findFirst();
            if((command.version()==1 && previous.isPresent()) || (command.version()>1 && (previous.isEmpty()
                    || previous.get().version()!=command.version()-1 || !previous.get().hash().equals(command.previousHash())
                    || !previous.get().channel().equals(command.channel()))))throw conflict("GRAPH_WRITE_VERSION_CONFLICT");
            run=store.lock(id).orElseThrow();admit(run,store.jsonBytes(canonical),0,1);
            store.write(id,new GraphStore.Write(command.checkpointId(),command.taskId(),command.index(),command.version(),
                    command.previousHash(),command.channel(),command.taskPath(),payloadHash,canonical),token);
            result.add(new Stored("ACCEPTED",payloadHash));
        }
        return result;
    }
    @Transactional
    public CheckpointView read(UUID id,String hash,UUID token,UUID checkpointId) {
        var run=lock(id,hash);active(run,token);
        var checkpoint=(checkpointId==null?store.latest(id):store.checkpoint(id,checkpointId)).orElseThrow(()->conflict("GRAPH_CHECKPOINT_MISSING"));
        return new CheckpointView(checkpoint.id(),checkpoint.parentId(),checkpoint.hash(),parse(checkpoint.envelope()),
                store.writes(id,checkpoint.id()).stream().map(w->new WriteView(w.taskId(),w.index(),w.version(),w.previousHash(),w.hash(),w.channel(),w.taskPath(),parse(w.payload()))).toList());
    }
    @Transactional
    public Waiting waitForHuman(UUID id,String hash,UUID token,GraphCommands.Wait command) {
        var run=lock(id,hash);
        if(command==null || !GraphPayloadValidator.hash(command.checkpointHash()) || !GraphPayloadValidator.hash(command.writeHash())
                || command.interruptId()==null || !command.interruptId().matches("[0-9a-f]{32,64}"))throw GraphPayloadValidator.invalid();
        var proof=new GraphStore.Waiting(command.interruptId(),command.checkpointId(),command.checkpointHash(),command.taskId(),
                command.writeVersion(),command.writeHash(),1,token);
        if(run.status().equals("WAITING_HUMAN")) {
            if(!current(run) || !store.waiting(id).orElseThrow().equals(proof))throw conflict("GRAPH_WAIT_CONFLICT");
            return waiting(proof);
        }
        active(run,token);
        if(!run.segment().equals("START"))throw conflict("GRAPH_REPEATED_INTERRUPT");
        var checkpoint=store.latest(id).orElseThrow(()->conflict("GRAPH_CHECKPOINT_MISSING"));
        if(!checkpoint.id().equals(command.checkpointId()) || !checkpoint.hash().equals(command.checkpointHash()))throw conflict("GRAPH_WAIT_CONFLICT");
        var write=store.writes(id,checkpoint.id()).stream().filter(w->w.taskId().equals(command.taskId()) && w.index()==-3).findFirst()
                .orElseThrow(()->conflict("GRAPH_WAIT_CONFLICT"));
        if(write.version()!=command.writeVersion() || !write.hash().equals(command.writeHash())
                || !write.channel().equals("__interrupt__") || !validator.interruptId(parse(write.payload())).equals(command.interruptId()))throw conflict("GRAPH_WAIT_CONFLICT");
        store.wait(id,proof);return waiting(proof);
    }
    GraphRun lock(UUID id,String hash) {
        enabled();var run=store.lock(id).orElseThrow(()->new AnalysisRunNotFoundException(id));
        if(!store.supported(id))throw conflict("GRAPH_VERSION_UNSUPPORTED");
        if(!GraphPayloadValidator.hash(hash) || !run.contextHash().equals(hash))throw conflict("GRAPH_INPUT_MISMATCH");return run;
    }
    void enabled() {if(!properties.enabled())throw conflict("GRAPH_DISABLED");}
    void active(GraphRun run,UUID token) {
        if(!run.status().equals("RUNNING") || token==null || !token.equals(run.token()) || !run.leaseActive())throw conflict("LEASE_CONFLICT");
        if(!current(run))throw conflict("STALE_INPUT");
        // Scope locks can block past lease expiry. Recheck database time after acquiring them.
        if(!store.owned(run.id(),token))throw conflict("LEASE_CONFLICT");
    }
    boolean current(GraphRun run) {
        var context=parse(run.context());var match=context.path("matchResult");
        policies.lockScopeRead(match.path("purchaseOrderId").asText());
        return store.current(run) && context.path("policyDocuments").equals(mapper.valueToTree(policies.scope(context.path("companyId").asText(),
                match.path("supplierId").asText(),match.path("purchaseOrderId").asText(),LocalDate.parse(context.path("applicableDate").asText()))));
    }
    GraphRun reviewRun(UUID caseId,UUID id) {
        var run=store.lock(id).filter(r->r.caseId().equals(caseId)).orElseThrow(()->new AnalysisRunNotFoundException(id));
        if(!store.supported(id))throw conflict("GRAPH_VERSION_UNSUPPORTED");return run;
    }
    private String bounded(JsonNode value) {
        String canonical=AnalysisCanonicalJson.canonicalize(value);
        if(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>GraphRun.MAX_BYTES || store.jsonBytes(canonical)>GraphRun.MAX_BYTES)
            throw conflict("GRAPH_STORAGE_LIMIT");return canonical;
    }
    private static void admit(GraphRun run,int bytes,int checkpoints,int writes) {
        if(run.checkpointCount()+checkpoints>GraphRun.MAX_CHECKPOINTS || run.writeCount()+writes>GraphRun.MAX_WRITES
                || run.storedBytes()+bytes>GraphRun.MAX_TOTAL_BYTES)throw conflict("GRAPH_STORAGE_LIMIT");
    }
    private JsonNode parse(String value) {try{return mapper.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid stored graph JSON");}}
    private static Waiting waiting(GraphStore.Waiting w) {return new Waiting(w.interruptId(),w.checkpointId(),w.checkpointHash(),w.taskId(),w.writeVersion(),w.writeHash(),w.reviewVersion());}
    private void references(UUID id,JsonNode value) {
        if(value.isObject())value.properties().forEach(e->{
            if(e.getKey().endsWith("StageRef"))reference(id,e.getKey(),e.getValue().asText());
            else if(e.getKey().equals("reviewRef"))reviewReference(id,e.getValue());
            else references(id,e.getValue());
        });
        else if(value.isArray())value.forEach(v->references(id,v));
    }
    private void reviewReference(UUID id,JsonNode value) {
        var review=store.review(id).orElseThrow(()->conflict("GRAPH_REVIEW_MISSING"));
        if(!value.isTextual() || !review.id().toString().equals(value.asText()) || !store.reviewConsumed(id,review.id()))throw conflict("GRAPH_RESUME_IDENTITY_MISMATCH");
    }
    private void reference(UUID id,String key,String ref) {
        String stage=switch(key) {
            case "executionStageRef"->"execution";case "documentStageRef"->"document";case "mappingStageRef"->"mapping";
            case "evidenceStageRef"->"evidence";case "resolutionStageRef"->"resolution";default->throw GraphPayloadValidator.invalid();
        };
        if(store.stages(id).stream().noneMatch(s->s.stage().equals(stage) && s.id().toString().equals(ref)))throw conflict("GRAPH_STAGE_MISMATCH");
    }
    static AnalysisConflictException conflict(String code) {return new AnalysisConflictException(code,"Graph execution conflicts with stored input or ownership");}
}
