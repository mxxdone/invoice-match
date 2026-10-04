package com.invoicematch.core.analysis.api;

import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoice-cases/{caseId}/proposals")
public class ProposalController {
    final ProposalService commands;final ProposalQueryService queries;
    public ProposalController(ProposalService commands,ProposalQueryService queries) {this.commands=commands;this.queries=queries;}
    @GetMapping public ResponseEntity<ProposalQueryService.Page> list(@PathVariable UUID caseId) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(caseId));}
    @GetMapping("/{id}") public ResponseEntity<ProposalQueryService.View> view(@PathVariable UUID caseId,@PathVariable UUID id) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.view(caseId,id));}
    @PostMapping public ResponseEntity<ProposalService.Reserved> reserve(@PathVariable UUID caseId,@RequestBody ProposalService.ReserveCommand body) {
        var result=commands.reserve(caseId,body);return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @ExceptionHandler(AnalysisConflictException.class) ResponseEntity<ApiError> conflict(AnalysisConflictException e) {return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new ApiError(e.code(),e.getMessage()));}
    @ExceptionHandler(AnalysisValidationException.class) ResponseEntity<ApiError> validation(AnalysisValidationException e) {return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new ApiError(e.code(),e.getMessage()));}
    @ExceptionHandler(AnalysisRunNotFoundException.class) ResponseEntity<ApiError> missing(AnalysisRunNotFoundException e) {return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).body(new ApiError("RUN_NOT_FOUND","Advisory run not found"));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception e) {return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new ApiError("MALFORMED_REQUEST","Invalid advisory request"));}
}
