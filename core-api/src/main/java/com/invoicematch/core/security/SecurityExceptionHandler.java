package com.invoicematch.core.security;

import com.invoicematch.core.invoicecase.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps authorization failures raised inside the MVC dispatch (for example a
 * role or ownership check in a controller) to the shared 403 JSON error body.
 * Authentication failures are handled earlier by the security filter chain and
 * become 401.
 */
@RestControllerAdvice
public class SecurityExceptionHandler {

    @ExceptionHandler(ForbiddenActionException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenActionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("FORBIDDEN", "Access is denied"));
    }
}
