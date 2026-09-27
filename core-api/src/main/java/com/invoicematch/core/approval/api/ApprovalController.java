package com.invoicematch.core.approval.api;

import com.invoicematch.core.approval.application.ApprovalApplicationService;
import com.invoicematch.core.approval.application.ApprovalResult;
import com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atomic approval API. Approving freezes the exact review snapshot the approver
 * saw into one APPROVED decision, one set of ReceiptAllocation rows and one
 * internal PaymentRequest, and moves the case to {@code EXPORT_PENDING}.
 *
 * <p>The actor is always the authenticated APPROVER; the request body carries no
 * actor, amount or allocation.
 */
@RestController
@RequestMapping("/api/invoice-cases")
public class ApprovalController {

    private final ApprovalApplicationService approvals;
    private final AuthorizationService authorization;

    public ApprovalController(ApprovalApplicationService approvals, AuthorizationService authorization) {
        this.approvals = approvals;
        this.authorization = authorization;
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApprovalResult> approve(
            @PathVariable UUID id, @Valid @RequestBody ApproveInvoiceCaseRequest request) {
        authorization.requireRole(Role.APPROVER);
        CommandResult<ApprovalResult> result = approvals.approve(new ApproveInvoiceCaseCommand(
                id,
                request.requestId(),
                request.expectedCaseVersion(),
                request.reviewSnapshotId(),
                request.reviewPayloadHash()));
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
