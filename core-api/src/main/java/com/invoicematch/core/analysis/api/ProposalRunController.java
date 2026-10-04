package com.invoicematch.core.analysis.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.document.application.DocumentFailure;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Machine-only transport. Core services own all policy, validation and storage. */
@RestController
@RequestMapping("/internal/proposal-runs")
public class ProposalRunController {
    final ProposalExecutionService execution;final ProposalSourceService sources;
    final ProposalToolService tools;final PolicySearchService policies;final ObjectMapper mapper;
    public ProposalRunController(ProposalExecutionService execution,ProposalSourceService sources,ProposalToolService tools,PolicySearchService policies,ObjectMapper mapper) {
        this.execution=execution;this.sources=sources;this.tools=tools;this.policies=policies;this.mapper=mapper;
    }
    record StepResponse(String stage,String hash,JsonNode payload) {}
    record ClaimResponse(String disposition,UUID token,java.time.Instant leaseUntil,JsonNode context,java.util.List<StepResponse> steps) {}
    private JsonNode parse(String text) {try {return mapper.readTree(text);}catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Stored advisory input is invalid");}}
    @PostMapping("/{id}/claim") public ResponseEntity<Object> claim(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash");var claim=execution.claim(id,hash(n));
        return ok(new ClaimResponse(claim.disposition(),claim.token(),claim.leaseUntil(),claim.context()==null?null:parse(claim.context()),
            claim.steps().stream().map(s->new StepResponse(s.stage(),s.hash(),parse(s.payload()))).toList()));
    }
    @PostMapping("/{id}/defer") public ResponseEntity<Object> defer(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash");return ok(execution.defer(id,hash(n)));
    }
    @PostMapping("/{id}/heartbeat") public ResponseEntity<Object> heartbeat(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash","token");return ok(Map.of("leaseUntil",execution.heartbeat(id,hash(n),uuid(n,"token"))));
    }
    @PostMapping("/{id}/calls") public ResponseEntity<Object> calls(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash","token","requestId","tokens");
        if(!n.path("tokens").isIntegralNumber() || !n.path("tokens").canConvertToInt()) throw invalid();
        return ok(Map.of("reserved",execution.reserveConfiguredCall(id,hash(n),uuid(n,"token"),uuid(n,"requestId"),n.path("tokens").asInt())));
    }
    @PostMapping("/{id}/checkpoints") public ResponseEntity<Object> checkpoint(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash","token","stage","payload");return ok(Map.of("disposition",execution.checkpoint(id,hash(n),uuid(n,"token"),text(n,"stage"),n.get("payload"))));
    }
    @PostMapping("/{id}/complete") public ResponseEntity<Object> complete(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash","token");var result=execution.complete(id,hash(n),uuid(n,"token"));return ok(Map.of("disposition","COMPLETED","proposalId",result.id(),"payloadHash",result.payloadHash()));
    }
    @PostMapping("/{id}/failures") public ResponseEntity<Object> failure(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash","token","errorCode");return ok(execution.failure(id,hash(n),uuid(n,"token"),text(n,"errorCode")));
    }
    @PostMapping("/{id}/tools") public ResponseEntity<Object> tools(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash","token","request");var r=n.path("request");keys(r,"requestId","tool","query","limit");
        return ok(tools.call(id,hash(n),uuid(n,"token"),new ProposalToolService.Request(uuid(r,"requestId"),text(r,"tool"),string(r,"query"),integer(r,"limit"))));
    }
    @PostMapping("/{id}/policies") public ResponseEntity<Object> policies(@PathVariable UUID id,@RequestBody JsonNode n) {
        keys(n,"contextHash","token","request");var r=n.path("request");keys(r,"requestId","query","mode","embeddingModel","embeddingVersion","embedding","limit");
        float[] embedding=null;
        if(!r.path("embedding").isNull()) {
            if(!r.path("embedding").isArray() || r.path("embedding").size()>3072) throw invalid();
            embedding=new float[r.path("embedding").size()];for(int i=0;i<embedding.length;i++) {var v=r.path("embedding").get(i);if(!v.isNumber() || !Double.isFinite(v.doubleValue())) throw invalid();embedding[i]=v.floatValue();}
        }
        return ok(policies.search(id,hash(n),uuid(n,"token"),new PolicySearchService.Request(uuid(r,"requestId"),text(r,"query"),text(r,"mode"),nullable(r,"embeddingModel"),nullable(r,"embeddingVersion"),embedding,integer(r,"limit"))));
    }
    @PostMapping("/{id}/documents/{documentId}/source") public ResponseEntity<byte[]> source(@PathVariable UUID id,@PathVariable UUID documentId,@RequestBody JsonNode n) {
        keys(n,"contextHash","token");var result=sources.read(id,hash(n),uuid(n,"token"),documentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .contentType(org.springframework.http.MediaType.parseMediaType(result.mediaType())).contentLength(result.bytes().length).body(result.bytes());
    }
    private static void keys(JsonNode n,String... fields) {if(n==null || !n.isObject()) throw invalid();Set<String> names=new HashSet<>();n.fieldNames().forEachRemaining(names::add);if(!names.equals(Set.of(fields))) throw invalid();}
    private static String string(JsonNode n,String field) {if(!n.path(field).isTextual()) throw invalid();return n.path(field).asText();}
    private static String text(JsonNode n,String field) {String v=string(n,field);if(v.isBlank()) throw invalid();return v;}
    private static String nullable(JsonNode n,String field) {return n.path(field).isNull()?null:text(n,field);}
    private static int integer(JsonNode n,String field) {if(!n.path(field).isIntegralNumber() || !n.path(field).canConvertToInt()) throw invalid();return n.path(field).asInt();}
    private static String hash(JsonNode n) {String h=text(n,"contextHash");if(!h.matches("[0-9a-f]{64}")) throw invalid();return h;}
    private static UUID uuid(JsonNode n,String field) {try {String s=text(n,field);UUID id=UUID.fromString(s);if(!id.toString().equals(s)) throw invalid();return id;}catch(IllegalArgumentException e) {throw invalid();}}
    private static AnalysisValidationException invalid() {return new AnalysisValidationException("MALFORMED_REQUEST","Invalid advisory request");}
    private static ResponseEntity<Object> ok(Object value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @ExceptionHandler(AnalysisValidationException.class) ResponseEntity<ApiError> validation(AnalysisValidationException e) {return error(HttpStatus.BAD_REQUEST,e.code(),e.getMessage());}
    @ExceptionHandler(AnalysisConflictException.class) ResponseEntity<ApiError> conflict(AnalysisConflictException e) {return error(HttpStatus.CONFLICT,e.code(),e.getMessage());}
    @ExceptionHandler(AnalysisRunNotFoundException.class) ResponseEntity<ApiError> missing(AnalysisRunNotFoundException e) {return error(HttpStatus.NOT_FOUND,"RUN_NOT_FOUND","Advisory run not found");}
    @ExceptionHandler(DocumentFailure.class) ResponseEntity<ApiError> sourceUnavailable(DocumentFailure e) {return error(HttpStatus.SERVICE_UNAVAILABLE,"SOURCE_UNAVAILABLE","Source is unavailable");}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<ApiError> malformed(Exception e) {return error(HttpStatus.BAD_REQUEST,"MALFORMED_REQUEST","Invalid advisory request");}
    private static ResponseEntity<ApiError> error(HttpStatus status,String code,String message) {return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code,message));}
}
