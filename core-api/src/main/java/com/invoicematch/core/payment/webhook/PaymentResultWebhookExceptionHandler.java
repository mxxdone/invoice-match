package com.invoicematch.core.payment.webhook;

import com.invoicematch.core.invoicecase.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps webhook failures to the stable JSON error contract with no side effect.
 * A signature failure is intentionally opaque; a conflict never changes an
 * existing record; an unknown key never creates a payment.
 */
@RestControllerAdvice
public class PaymentResultWebhookExceptionHandler {

    @ExceptionHandler(WebhookSignatureException.class)
    public ResponseEntity<ApiError> handleSignature(WebhookSignatureException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("INVALID_SIGNATURE", "The webhook signature could not be verified"));
    }

    @ExceptionHandler(PaymentResultValidationException.class)
    public ResponseEntity<ApiError> handleValidation(PaymentResultValidationException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_PAYMENT_RESULT", e.getMessage()));
    }

    @ExceptionHandler(UnknownPaymentKeyException.class)
    public ResponseEntity<ApiError> handleUnknownKey(UnknownPaymentKeyException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("UNKNOWN_PAYMENT_KEY", e.getMessage()));
    }

    @ExceptionHandler(PaymentResultConflictException.class)
    public ResponseEntity<ApiError> handleConflict(PaymentResultConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("PAYMENT_RESULT_CONFLICT", e.getMessage()));
    }
}
