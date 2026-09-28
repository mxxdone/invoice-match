package com.invoicematch.core.invoicecase.api;

import com.invoicematch.core.invoicecase.application.IdempotencyConflictException;
import com.invoicematch.core.invoicecase.domain.CaseStateConflictException;
import com.invoicematch.core.invoicecase.domain.DraftNotEditableException;
import com.invoicematch.core.invoicecase.domain.EvidenceBundleNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvalidStateTransitionException;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.domain.StaleCaseVersionException;
import com.invoicematch.core.purchasingreference.domain.ExternalFactUnconfirmedException;
import com.invoicematch.core.purchasingreference.domain.ExternalReferenceMismatchException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderNotFoundException;
import com.invoicematch.core.purchasingreference.domain.PurchasingReferenceException;
import com.invoicematch.core.purchasingreference.domain.PurchasingSystemUnavailableException;
import com.invoicematch.core.purchasingreference.domain.ReceiptAllocationProtectedException;
import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.NumericOverflowException;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps invoice case command failures to a small, stable JSON error contract.
 * Validation failures are 400, optimistic/idempotency/state conflicts are 409,
 * missing resources are 404 and external purchasing failures are 503/502.
 */
@RestControllerAdvice
public class InvoiceCaseExceptionHandler {

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ApiError> handleValidation(DomainValidationException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
    }

    @ExceptionHandler(NumericOverflowException.class)
    public ResponseEntity<ApiError> handleOverflow(NumericOverflowException e) {
        return error(HttpStatus.BAD_REQUEST, "NUMERIC_OVERFLOW", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Request validation failed";
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformedRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request could not be parsed");
    }

    @ExceptionHandler({InvoiceCaseNotFoundException.class, EvidenceBundleNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(PurchaseOrderNotFoundException.class)
    public ResponseEntity<ApiError> handlePurchaseOrderNotFound(PurchaseOrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "PURCHASE_ORDER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(StaleCaseVersionException.class)
    public ResponseEntity<CaseVersionConflictError> handleStaleVersion(StaleCaseVersionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new CaseVersionConflictError(
                        "STALE_CASE_VERSION",
                        e.getMessage(),
                        e.caseId().toString(),
                        e.expectedVersion(),
                        e.actualVersion()));
    }

    @ExceptionHandler(DraftNotEditableException.class)
    public ResponseEntity<ApiError> handleDraftNotEditable(DraftNotEditableException e) {
        return error(HttpStatus.CONFLICT, "DRAFT_NOT_EDITABLE", e.getMessage());
    }

    @ExceptionHandler({CaseStateConflictException.class, InvalidStateTransitionException.class})
    public ResponseEntity<ApiError> handleStateConflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, "CASE_STATE_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<CaseVersionConflictError> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new CaseVersionConflictError(
                        "STALE_CASE_VERSION",
                        "The invoice case was modified concurrently",
                        null,
                        null,
                        null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with the current state");
    }

    @ExceptionHandler(ExternalReferenceMismatchException.class)
    public ResponseEntity<ApiError> handleExternalMismatch(ExternalReferenceMismatchException e) {
        return error(HttpStatus.CONFLICT, "EXTERNAL_REFERENCE_MISMATCH", e.getMessage());
    }

    @ExceptionHandler(ExternalFactUnconfirmedException.class)
    public ResponseEntity<ApiError> handleExternalUnconfirmed(ExternalFactUnconfirmedException e) {
        return error(HttpStatus.CONFLICT, "EXTERNAL_FACT_UNCONFIRMED", e.getMessage());
    }

    @ExceptionHandler(PurchasingSystemUnavailableException.class)
    public ResponseEntity<ApiError> handlePurchasingUnavailable(PurchasingSystemUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASING_SYSTEM_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(ReceiptAllocationProtectedException.class)
    public ResponseEntity<ApiError> handleReceiptAllocationProtected(ReceiptAllocationProtectedException e) {
        return error(HttpStatus.CONFLICT, "RECEIPT_ALLOCATION_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(PurchasingReferenceException.class)
    public ResponseEntity<ApiError> handlePurchasingReference(PurchasingReferenceException e) {
        return error(HttpStatus.BAD_GATEWAY, "PURCHASING_REFERENCE_ERROR", e.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
