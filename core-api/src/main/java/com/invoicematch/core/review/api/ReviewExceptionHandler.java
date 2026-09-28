package com.invoicematch.core.review.api;

import com.invoicematch.core.review.domain.ReviewSnapshotNotFoundException;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import com.invoicematch.core.review.domain.ReviewTargetInvalidException;
import com.invoicematch.core.review.domain.StaleReviewTargetException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps review workflow failures to the stable JSON error contract. A stale
 * target returns 409 with the explicit list of reasons and the committed case
 * version/status the UI must re-read; an invalid mapping target returns 409 with
 * no partial rows written.
 */
@RestControllerAdvice
public class ReviewExceptionHandler {

    @ExceptionHandler(ReviewSnapshotNotFoundException.class)
    public ResponseEntity<ReviewConflictError> handleNotFound(ReviewSnapshotNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "REVIEW_SNAPSHOT_NOT_FOUND", e.getMessage(), List.of(), null, null);
    }

    @ExceptionHandler(StaleReviewTargetException.class)
    public ResponseEntity<ReviewConflictError> handleStale(StaleReviewTargetException e) {
        List<String> reasons = e.reasons().stream().map(Enum::name).toList();
        return error(
                HttpStatus.CONFLICT,
                "STALE_REVIEW_TARGET",
                e.getMessage(),
                reasons,
                e.currentCaseVersion(),
                e.currentCaseStatus());
    }

    @ExceptionHandler(ReviewStateConflictException.class)
    public ResponseEntity<ReviewConflictError> handleStateConflict(ReviewStateConflictException e) {
        return error(HttpStatus.CONFLICT, "REVIEW_STATE_CONFLICT", e.getMessage(), List.of(), null, null);
    }

    @ExceptionHandler(ReviewTargetInvalidException.class)
    public ResponseEntity<ReviewConflictError> handleTargetInvalid(ReviewTargetInvalidException e) {
        return error(HttpStatus.CONFLICT, "REVIEW_TARGET_INVALID", e.getMessage(), List.of(), null, null);
    }

    private static ResponseEntity<ReviewConflictError> error(
            HttpStatus status,
            String code,
            String message,
            List<String> reasons,
            Long currentCaseVersion,
            String currentCaseStatus) {
        return ResponseEntity.status(status)
                .body(new ReviewConflictError(code, message, reasons, currentCaseVersion, currentCaseStatus));
    }
}
