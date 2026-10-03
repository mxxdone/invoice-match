package com.invoicematch.core.analysis.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.BusyRunResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.ClaimedRunResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.DispositionResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.HeartbeatResponse;
import com.invoicematch.core.analysis.api.AnalysisMachineViews.ResultDispositionResponse;
import com.invoicematch.core.analysis.application.AnalysisClaimCommand;
import com.invoicematch.core.analysis.application.AnalysisConflictException;
import com.invoicematch.core.analysis.application.AnalysisDocumentResultCommand;
import com.invoicematch.core.analysis.application.AnalysisExecutionService;
import com.invoicematch.core.analysis.application.AnalysisHeartbeatCommand;
import com.invoicematch.core.analysis.application.AnalysisRunNotFoundException;
import com.invoicematch.core.analysis.application.AnalysisValidationException;
import com.invoicematch.core.analysis.application.ClaimOutcome;
import com.invoicematch.core.analysis.application.HeartbeatOutcome;
import com.invoicematch.core.analysis.application.ResultOutcome;
import com.invoicematch.core.invoicecase.api.ApiError;
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
 * Every response and error is {@code no-store}.
 */
@RestController
@RequestMapping("/internal/analysis-runs")
public class AnalysisRunController {

    private final AnalysisExecutionService service;

    public AnalysisRunController(AnalysisExecutionService service) {
        this.service = service;
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
    public ResponseEntity<Object> claim(@PathVariable UUID id, @RequestBody ClaimRequest request) {
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
    public ResponseEntity<Object> heartbeat(@PathVariable UUID id, @RequestBody HeartbeatRequest request) {
        HeartbeatOutcome outcome = service.heartbeat(id, new AnalysisHeartbeatCommand(
                request.claimToken(), request.inputVersion(), request.evidencePayloadHash()));
        return noStore(new HeartbeatResponse(outcome.leaseUntil()));
    }

    @PostMapping("/{id}/results")
    public ResponseEntity<Object> results(@PathVariable UUID id, @RequestBody ResultRequest request) {
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

    private static ResponseEntity<Object> noStore(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code, message));
    }
}
