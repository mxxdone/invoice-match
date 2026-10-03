package com.invoicematch.core.analysis.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.BusyRunResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.ClaimedRunResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.DispositionResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.HeartbeatResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.ResultDispositionResponse;
import com.invoicematch.core.analysis.application.AnalysisClaimCommand;
import com.invoicematch.core.analysis.application.AnalysisConflictException;
import com.invoicematch.core.analysis.application.AnalysisDocumentResultCommand;
import com.invoicematch.core.analysis.application.AnalysisExecutionService;
import com.invoicematch.core.analysis.application.AnalysisSourceService;
import com.invoicematch.core.document.application.DocumentFailure;
import com.invoicematch.core.analysis.application.AnalysisHeartbeatCommand;
import com.invoicematch.core.analysis.application.AnalysisRunNotFoundException;
import com.invoicematch.core.analysis.application.AnalysisValidationException;
import com.invoicematch.core.analysis.application.ClaimOutcome;
import com.invoicematch.core.analysis.application.HeartbeatOutcome;
import com.invoicematch.core.analysis.application.ResultOutcome;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Machine worker HTTP surface for the analysis execution lifecycle. It only maps
 * the request body to an application command and the application outcome to an
 * HTTP/JSON shape; all authentication, validation and state policy lives below.
 * The machine request envelope is strict: an unknown added field is rejected
 * rather than silently dropped. Every response and error is {@code no-store}.
 */
@RestController
@RequestMapping("/internal/analysis-runs")
public class AnalysisRunController {

    private static final Set<String> CLAIM_KEYS =
            Set.of("eventId", "inputVersion", "evidencePayloadHash", "workflowVersion");
    private static final Set<String> HEARTBEAT_KEYS =
            Set.of("claimToken", "inputVersion", "evidencePayloadHash");
    private static final Set<String> RESULT_KEYS = Set.of(
            "claimToken", "inputVersion", "evidencePayloadHash", "documentId", "outcome", "result", "errorCode");

    private final AnalysisExecutionService service;
    private final ObjectMapper mapper;
    private final AnalysisSourceService sources;

    public AnalysisRunController(AnalysisExecutionService service, ObjectMapper mapper, AnalysisSourceService sources) {
        this.service = service;
        this.mapper = mapper;
        this.sources = sources;
    }

    public record ClaimRequest(
            UUID eventId, Integer inputVersion, String evidencePayloadHash, String workflowVersion) {
    }

    public record HeartbeatRequest(UUID claimToken, Integer inputVersion, String evidencePayloadHash) {
    }

    public record ResultRequest(
            UUID claimToken,
            Integer inputVersion,
            String evidencePayloadHash,
            UUID documentId,
            String outcome,
            JsonNode result,
            String errorCode) {
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<Object> claim(@PathVariable UUID id, @RequestBody JsonNode body) {
        ClaimRequest request = readEnvelope(body, CLAIM_KEYS, ClaimRequest.class);
        ClaimOutcome outcome = service.claim(id, new AnalysisClaimCommand(
                request.eventId(), request.inputVersion(), request.evidencePayloadHash(), request.workflowVersion()));
        return switch (outcome) {
            case ClaimOutcome.Claimed claimed -> noStore(ClaimedRunResponse.from(claimed));
            case ClaimOutcome.Busy busy -> noStore(BusyRunResponse.from(busy));
            case ClaimOutcome.AlreadyFinished ignored -> noStore(DispositionResponse.alreadyFinished());
            case ClaimOutcome.Stale ignored -> noStore(DispositionResponse.stale());
        };
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Object> heartbeat(@PathVariable UUID id, @RequestBody JsonNode body) {
        HeartbeatRequest request = readEnvelope(body, HEARTBEAT_KEYS, HeartbeatRequest.class);
        HeartbeatOutcome outcome = service.heartbeat(id, new AnalysisHeartbeatCommand(
                request.claimToken(), request.inputVersion(), request.evidencePayloadHash()));
        return noStore(new HeartbeatResponse(outcome.leaseUntil()));
    }

    @PostMapping("/{id}/documents/{documentId}/source")
    public ResponseEntity<byte[]> source(@PathVariable UUID id, @PathVariable UUID documentId,
            @RequestBody JsonNode body) {
        HeartbeatRequest request = readEnvelope(body, HEARTBEAT_KEYS, HeartbeatRequest.class);
        var source = sources.read(id, documentId, new AnalysisHeartbeatCommand(
                request.claimToken(), request.inputVersion(), request.evidencePayloadHash()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.parseMediaType(source.mediaType()))
                .contentLength(source.bytes().length).body(source.bytes());
    }

    @ExceptionHandler(DocumentFailure.class)
    public ResponseEntity<ApiError> sourceUnavailable(DocumentFailure e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE", "Source is unavailable");
    }

    @PostMapping("/{id}/results")
    public ResponseEntity<Object> results(@PathVariable UUID id, @RequestBody JsonNode body) {
        ResultRequest request = readEnvelope(body, RESULT_KEYS, ResultRequest.class);
        ResultOutcome outcome = service.recordResult(id, new AnalysisDocumentResultCommand(
                request.claimToken(),
                request.inputVersion(),
                request.evidencePayloadHash(),
                request.documentId(),
                request.outcome(),
                request.result(),
                request.errorCode()));
        return switch (outcome) {
            case ResultOutcome.Accepted accepted -> noStore(
                    new ResultDispositionResponse("ACCEPTED", accepted.runStatus().name()));
            case ResultOutcome.Replayed replayed -> noStore(
                    new ResultDispositionResponse("REPLAYED", replayed.runStatus().name()));
            case ResultOutcome.Stale ignored -> noStore(DispositionResponse.stale());
        };
    }

    @ExceptionHandler(AnalysisRunNotFoundException.class)
    public ResponseEntity<ApiError> notFound(AnalysisRunNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Analysis run not found");
    }

    @ExceptionHandler(AnalysisValidationException.class)
    public ResponseEntity<ApiError> validation(AnalysisValidationException e) {
        return error(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
    }

    @ExceptionHandler(AnalysisConflictException.class)
    public ResponseEntity<ApiError> conflict(AnalysisConflictException e) {
        return error(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformed(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request could not be parsed");
    }

    private <T> T readEnvelope(JsonNode body, Set<String> allowed, Class<T> type) {
        if (body == null || !body.isObject()) {
            throw new AnalysisValidationException("MALFORMED_REQUEST", "the request body must be a JSON object");
        }
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new AnalysisValidationException(
                        "MALFORMED_REQUEST", "the request contains an unknown field");
            }
        });
        JsonNode inputVersion = body.get("inputVersion");
        if (inputVersion != null && !inputVersion.isNull()
                && (!inputVersion.isIntegralNumber() || !inputVersion.canConvertToInt())) {
            throw new AnalysisValidationException("MALFORMED_REQUEST", "inputVersion must be an integer");
        }
        for (String field : allowed) {
            if (field.equals("result") || field.equals("inputVersion")) {
                continue;
            }
            JsonNode value = body.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw new AnalysisValidationException("MALFORMED_REQUEST", "request fields have invalid types");
            }
        }
        try {
            return mapper.treeToValue(body, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new AnalysisValidationException("MALFORMED_REQUEST", "the request could not be parsed");
        }
    }

    private static ResponseEntity<Object> noStore(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code, message));
    }
}
