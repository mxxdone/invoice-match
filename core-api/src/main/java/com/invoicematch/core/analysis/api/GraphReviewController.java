package com.invoicematch.core.analysis.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Human write surface; the worker bearer token never grants this permission. */
@RestController
@RequestMapping("/api/invoice-cases/{caseId}/graphs/{id}/reviews")
public class GraphReviewController {
    private final GraphReviewService reviews;
    public GraphReviewController(GraphReviewService reviews) {this.reviews=reviews;}
    @PostMapping public ResponseEntity<GraphReviewService.Saved> confirm(@PathVariable UUID caseId,@PathVariable UUID id,@RequestBody JsonNode n) {
        GraphPayloadValidator.keys(n,"requestId","expectedCaseVersion","interruptId","checkpointHash","reviewVersion","confirmation","reason");
        if(!n.path("expectedCaseVersion").isIntegralNumber() || !n.path("expectedCaseVersion").canConvertToLong()
                || !n.path("reviewVersion").isIntegralNumber() || !n.path("reviewVersion").canConvertToInt())throw malformed();
        var result=reviews.confirm(caseId,id,new GraphReviewService.Command(text(n,"requestId"),n.path("expectedCaseVersion").longValue(),
                text(n,"interruptId"),text(n,"checkpointHash"),n.path("reviewVersion").intValue(),n.get("confirmation"),text(n,"reason")));
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @ExceptionHandler(AnalysisConflictException.class) ResponseEntity<ApiError> conflict(AnalysisConflictException e) {return error(409,e.code(),e.getMessage());}
    @ExceptionHandler(AnalysisValidationException.class) ResponseEntity<ApiError> validation(AnalysisValidationException e) {return error(400,e.code(),e.getMessage());}
    @ExceptionHandler(AnalysisRunNotFoundException.class) ResponseEntity<ApiError> missing(AnalysisRunNotFoundException e) {return error(404,"RUN_NOT_FOUND","Graph run not found");}
    private static String text(JsonNode n,String field) {return GraphPayloadValidator.text(n,field);}
    private static AnalysisValidationException malformed() {return new AnalysisValidationException("MALFORMED_REQUEST","Invalid graph review request");}
    private static ResponseEntity<ApiError> error(int status,String code,String message) {return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code,message));}
}
