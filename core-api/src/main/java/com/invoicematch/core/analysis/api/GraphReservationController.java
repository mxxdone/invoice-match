package com.invoicematch.core.analysis.api;

import com.invoicematch.core.analysis.application.GraphExecutionService;
import com.invoicematch.core.analysis.application.ProposalService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoice-cases/{caseId}/graphs")
public class GraphReservationController {
    private final GraphExecutionService execution;
    public GraphReservationController(GraphExecutionService execution) {this.execution=execution;}
    @PostMapping public ResponseEntity<GraphExecutionService.Reserved> reserve(@PathVariable UUID caseId,@RequestBody ProposalService.ReserveCommand command) {
        var result=execution.reserve(caseId,command);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @PostMapping("/{predecessor}/successors") public ResponseEntity<GraphExecutionService.Reserved> successor(
            @PathVariable UUID caseId,@PathVariable UUID predecessor,@RequestBody ProposalService.ReserveCommand command) {
        var result=execution.successor(caseId,predecessor,command);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
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
