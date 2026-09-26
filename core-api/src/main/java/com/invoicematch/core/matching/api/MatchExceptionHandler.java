package com.invoicematch.core.matching.api;

import com.invoicematch.core.invoicecase.api.ApiError;
import com.invoicematch.core.matching.domain.MatchResultNotFoundException;
import com.invoicematch.core.matching.domain.MatchStateConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps matching-specific failures to the same stable JSON error contract the
 * invoice case API uses. Case-level 404/409/validation failures are already
 * handled by the invoice case advice.
 */
@RestControllerAdvice
public class MatchExceptionHandler {

    @ExceptionHandler(MatchStateConflictException.class)
    public ResponseEntity<ApiError> handleStateConflict(MatchStateConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("MATCH_STATE_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(MatchResultNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(MatchResultNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("MATCH_RESULT_NOT_FOUND", e.getMessage()));
    }
}
