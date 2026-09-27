package com.invoicematch.core.approval.api;

import com.invoicematch.core.approval.domain.ApprovalNotPermittedException;
import com.invoicematch.core.approval.domain.InsufficientReceiptBalanceException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps approval failures to the stable JSON error contract. Both an
 * unapprovable snapshot and an exhausted receipt balance return 409 with no
 * side effect; a shortfall response carries the current confirmed/allocated/
 * remaining details so the caller can explain why the approval lost the race.
 */
@RestControllerAdvice
public class ApprovalExceptionHandler {

    @ExceptionHandler(ApprovalNotPermittedException.class)
    public ResponseEntity<ApprovalConflictError> handleNotPermitted(ApprovalNotPermittedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApprovalConflictError(
                        "APPROVAL_NOT_PERMITTED", e.getMessage(), e.reasons(), List.of()));
    }

    @ExceptionHandler(InsufficientReceiptBalanceException.class)
    public ResponseEntity<ApprovalConflictError> handleInsufficient(InsufficientReceiptBalanceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApprovalConflictError(
                        "INSUFFICIENT_RECEIPT_BALANCE",
                        e.getMessage(),
                        List.of(),
                        List.copyOf(e.shortfalls())));
    }
}
