package com.invoicematch.core.analysis.api;

import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis-runs")
public class AnalysisOperationsController {
    private final AnalysisOperationsService service;
    public AnalysisOperationsController(AnalysisOperationsService service) { this.service=service; }
    @GetMapping
    public ResponseEntity<?> jobs(@RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ok(service.jobs(status,page,size));
    }
    @GetMapping("/{id}/failures")
    public ResponseEntity<?> failures(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return ok(service.failures(id,page,size)); }
    @PostMapping("/{id}/retries")
    public ResponseEntity<?> retry(@PathVariable UUID id,@RequestBody AnalysisOperationsService.RetryCommand command) {
        var result=service.retry(id,command);return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @ExceptionHandler(AnalysisValidationException.class)
    public ResponseEntity<?> validation(AnalysisValidationException e) { return error(400,e.code(),e.getMessage()); }
    @ExceptionHandler(AnalysisConflictException.class)
    public ResponseEntity<?> conflict(AnalysisConflictException e) { return error(409,e.code(),e.getMessage()); }
    @ExceptionHandler(AnalysisRunNotFoundException.class)
    public ResponseEntity<?> missing(AnalysisRunNotFoundException e) { return error(404,"RUN_NOT_FOUND","Analysis run not found"); }
    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code,message));
    }
}
