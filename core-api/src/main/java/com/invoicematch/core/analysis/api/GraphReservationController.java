package com.invoicematch.core.analysis.api;

import com.invoicematch.core.analysis.application.AnalysisRunNotFoundException;
import com.invoicematch.core.analysis.application.GraphExecutionService;
import com.invoicematch.core.analysis.application.GraphQueryService;
import com.invoicematch.core.analysis.application.GraphViews;
import com.invoicematch.core.analysis.application.ProposalService;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoice-cases/{caseId}/graphs")
public class GraphReservationController {
    private final GraphExecutionService execution;
    private final GraphQueryService queries;
    public GraphReservationController(GraphExecutionService execution,GraphQueryService queries) {this.execution=execution;this.queries=queries;}
    @GetMapping public ResponseEntity<GraphViews.Page> list(@PathVariable UUID caseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(caseId));
    }
    @GetMapping("/{id}") public ResponseEntity<GraphViews.View> view(@PathVariable UUID caseId,@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.view(caseId,id));
    }
    @PostMapping public ResponseEntity<GraphExecutionService.Reserved> reserve(@PathVariable UUID caseId,@RequestBody ProposalService.ReserveCommand command) {
        var result=execution.reserve(caseId,command);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @PostMapping("/{predecessor}/successors") public ResponseEntity<GraphExecutionService.Reserved> successor(
            @PathVariable UUID caseId,@PathVariable UUID predecessor,@RequestBody ProposalService.ReserveCommand command) {
        var result=execution.successor(caseId,predecessor,command);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @ExceptionHandler(AnalysisRunNotFoundException.class)
    ResponseEntity<ApiError> missing(AnalysisRunNotFoundException e) {
        return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).body(new ApiError("RUN_NOT_FOUND","Graph run not found"));
    }
    @ExceptionHandler(com.invoicematch.core.analysis.application.AnalysisConflictException.class)
    ResponseEntity<com.invoicematch.core.invoicecase.api.ApiError> conflict(com.invoicematch.core.analysis.application.AnalysisConflictException e) {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(new com.invoicematch.core.invoicecase.api.ApiError(e.code(),e.getMessage()));
    }
    @ExceptionHandler(com.invoicematch.core.analysis.application.AnalysisValidationException.class)
    ResponseEntity<com.invoicematch.core.invoicecase.api.ApiError> invalid(com.invoicematch.core.analysis.application.AnalysisValidationException e) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new com.invoicematch.core.invoicecase.api.ApiError(e.code(),e.getMessage()));
    }
}
