package com.invoicematch.core.analysis.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.application.AnalysisConflictException;
import com.invoicematch.core.analysis.application.AnalysisRunNotFoundException;
import com.invoicematch.core.analysis.application.AnalysisValidationException;
import com.invoicematch.core.analysis.application.GraphCommands;
import com.invoicematch.core.analysis.application.GraphExecutionService;
import com.invoicematch.core.analysis.application.GraphPayloadValidator;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Machine-only graph persistence. This controller never exposes a database or SDK handle. */
@RestController
@RequestMapping("/internal/graph-runs")
public class GraphRunController {
    private final GraphExecutionService execution;
    private final com.invoicematch.core.analysis.application.GraphStageService stages;
    private final com.invoicematch.core.analysis.application.GraphSourceService sources;
    public GraphRunController(GraphExecutionService execution,com.invoicematch.core.analysis.application.GraphStageService stages,com.invoicematch.core.analysis.application.GraphSourceService sources) { this.execution=execution;this.stages=stages;this.sources=sources; }

    @PostMapping("/{id}/stages/read") public ResponseEntity<Object> stages(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token");return ok(stages.read(id,hash(n),uuid(n,"token")));
    }
    @PostMapping("/{id}/stages") public ResponseEntity<Object> stage(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","stage","payload");return ok(stages.save(id,hash(n),uuid(n,"token"),text(n,"stage"),n.get("payload")));
    }
    @PostMapping("/{id}/calls") public ResponseEntity<Object> calls(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","requestId","tokens");
        return ok(Map.of("reserved",stages.reserve(id,hash(n),uuid(n,"token"),uuid(n,"requestId"),integer(n,"tokens"))));
    }
    @PostMapping("/{id}/tools") public ResponseEntity<Object> tools(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","request");var r=n.path("request");
        GraphPayloadValidator.keys(r,"requestId","tool","query","limit");
        return ok(stages.tool(id,hash(n),uuid(n,"token"),new com.invoicematch.core.analysis.application.ProposalToolService.Request(
            uuid(r,"requestId"),text(r,"tool"),string(r,"query"),integer(r,"limit"))));
    }
    @PostMapping("/{id}/policies") public ResponseEntity<Object> policies(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","request");var r=n.path("request");
        GraphPayloadValidator.keys(r,"requestId","query","mode","embeddingModel","embeddingVersion","embedding","limit");
        float[] embedding=null;
        if(!r.path("embedding").isNull()) {
            if(!r.path("embedding").isArray() || r.path("embedding").size()>3072)throw invalid();
            embedding=new float[r.path("embedding").size()];for(int i=0;i<embedding.length;i++) {
                var v=r.path("embedding").get(i);if(!v.isNumber() || !Double.isFinite(v.doubleValue()))throw invalid();embedding[i]=v.floatValue();
            }
        }
        return ok(stages.policy(id,hash(n),uuid(n,"token"),new com.invoicematch.core.analysis.application.PolicySearchService.Request(
            uuid(r,"requestId"),text(r,"query"),text(r,"mode"),r.path("embeddingModel").isNull()?null:text(r,"embeddingModel"),
            r.path("embeddingVersion").isNull()?null:text(r,"embeddingVersion"),embedding,integer(r,"limit"))));
    }
    @PostMapping("/{id}/complete") public ResponseEntity<Object> complete(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token");var result=stages.complete(id,hash(n),uuid(n,"token"));
        return ok(Map.of("disposition","COMPLETED","proposalId",id,"payloadHash",result.hash()));
    }
    @PostMapping("/{id}/failures") public ResponseEntity<Object> failure(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","errorCode");
        return ok(Map.of("disposition","CHECKPOINTED","runStatus",stages.failure(id,hash(n),uuid(n,"token"),text(n,"errorCode"))));
    }

    @PostMapping("/{id}/documents/{documentId}/source") public ResponseEntity<byte[]> source(@PathVariable UUID id,@PathVariable UUID documentId,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token");var result=sources.read(id,hash(n),uuid(n,"token"),documentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .contentType(org.springframework.http.MediaType.parseMediaType(result.mediaType())).contentLength(result.bytes().length).body(result.bytes());
    }
    @PostMapping("/{id}/claim") public ResponseEntity<Object> claim(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash");return ok(execution.claim(id,hash(n)));
    }
    @PostMapping("/{id}/heartbeat") public ResponseEntity<Object> heartbeat(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token");return ok(Map.of("leaseUntil",execution.heartbeat(id,hash(n),uuid(n,"token"))));
    }
    @PostMapping("/{id}/checkpoints") public ResponseEntity<Object> checkpoint(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","checkpoint");var c=n.path("checkpoint");
        GraphPayloadValidator.keys(c,"threadId","graphVersion","serializerVersion","checkpointSchema","checkpointId","parentId","body","metadata","newVersions");
        return ok(execution.checkpoint(id,hash(n),uuid(n,"token"),new GraphCommands.Checkpoint(uuid(c,"threadId"),
                text(c,"graphVersion"),text(c,"serializerVersion"),integer(c,"checkpointSchema"),uuid(c,"checkpointId"),
                nullableUuid(c,"parentId"),c.get("body"),c.get("metadata"),c.get("newVersions"))));
    }
    @PostMapping("/{id}/writes") public ResponseEntity<Object> writes(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","writes");
        if(!n.path("writes").isArray())throw invalid();var writes=new ArrayList<GraphCommands.Write>();
        for(var w:n.path("writes")) {
            GraphPayloadValidator.keys(w,"checkpointId","taskId","index","version","previousHash","channel","taskPath","payload");
            writes.add(new GraphCommands.Write(uuid(w,"checkpointId"),uuid(w,"taskId"),integer(w,"index"),integer(w,"version"),
                    w.path("previousHash").isNull()?null:text(w,"previousHash"),text(w,"channel"),string(w,"taskPath"),w.get("payload")));
        }
        return ok(execution.writes(id,hash(n),uuid(n,"token"),writes));
    }
    @PostMapping("/{id}/checkpoints/read") public ResponseEntity<Object> read(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","checkpointId");
        return ok(execution.read(id,hash(n),uuid(n,"token"),nullableUuid(n,"checkpointId")));
    }
    @PostMapping("/{id}/waiting") public ResponseEntity<Object> waiting(@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"contextHash","token","interrupt");var w=n.path("interrupt");
        GraphPayloadValidator.keys(w,"checkpointId","checkpointHash","taskId","writeVersion","writeHash","interruptId");
        return ok(execution.waitForHuman(id,hash(n),uuid(n,"token"),new GraphCommands.Wait(uuid(w,"checkpointId"),
                text(w,"checkpointHash"),uuid(w,"taskId"),integer(w,"writeVersion"),text(w,"writeHash"),text(w,"interruptId"))));
    }
    private static String text(JsonNode n,String field) {return GraphPayloadValidator.text(n,field);}
    private static String string(JsonNode n,String field) {if(!n.path(field).isTextual())throw invalid();return n.path(field).asText();}
    private static UUID uuid(JsonNode n,String field) {return GraphPayloadValidator.uuid(text(n,field));}
    private static UUID nullableUuid(JsonNode n,String field) {return n.path(field).isNull()?null:uuid(n,field);}
    private static int integer(JsonNode n,String field) {if(!n.path(field).isIntegralNumber() || !n.path(field).canConvertToInt())throw invalid();return n.path(field).asInt();}
    private static String hash(JsonNode n) {var h=text(n,"contextHash");if(!GraphPayloadValidator.hash(h))throw invalid();return h;}
    private static AnalysisValidationException invalid() {return new AnalysisValidationException("MALFORMED_REQUEST","Invalid graph request");}
    private static ResponseEntity<Object> ok(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @ExceptionHandler(AnalysisValidationException.class) ResponseEntity<ApiError> validation(AnalysisValidationException e) {return error(HttpStatus.BAD_REQUEST,e.code(),e.getMessage());}
    @ExceptionHandler(AnalysisConflictException.class) ResponseEntity<ApiError> conflict(AnalysisConflictException e) {return error(HttpStatus.CONFLICT,e.code(),e.getMessage());}
    @ExceptionHandler(AnalysisRunNotFoundException.class) ResponseEntity<ApiError> missing(AnalysisRunNotFoundException e) {return error(HttpStatus.NOT_FOUND,"RUN_NOT_FOUND","Graph run not found");}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception e) {return error(HttpStatus.BAD_REQUEST,"MALFORMED_REQUEST","Invalid graph request");}
    private static ResponseEntity<ApiError> error(HttpStatus status,String code,String message) {return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code,message));}
}
