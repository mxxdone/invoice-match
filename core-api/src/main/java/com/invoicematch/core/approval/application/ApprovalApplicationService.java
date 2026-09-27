package com.invoicematch.core.approval.application;

import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.IdempotencyConflictException;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.purchasingreference.application.PreparedPurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand;
import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the approval write. The external purchasing fetch and validation
 * happen here, before the approval database transaction is opened, so no HTTP
 * call is ever made while a transaction, row or advisory lock is held
 * (Spec 13.1, ADR 0002).
 *
 * <p>A completed replay is detected first and returns the stored response
 * without an external call. The write transaction then applies the prepared
 * snapshot atomically and reserves the actor-scoped request id.
 */
@Service
public class ApprovalApplicationService {

    private final PurchasingReferenceService purchasingReferenceService;
    private final ApprovalService approvalService;
    private final RequestIdempotencyStore idempotency;
    private final ApprovalCommandFingerprint fingerprint;
    private final AuthorizationService authorization;
    private final InvoiceCaseRepository invoiceCases;
    private final ApprovalInterceptor interceptor;

    public ApprovalApplicationService(
            PurchasingReferenceService purchasingReferenceService,
            ApprovalService approvalService,
            RequestIdempotencyStore idempotency,
            ApprovalCommandFingerprint fingerprint,
            AuthorizationService authorization,
            InvoiceCaseRepository invoiceCases,
            ObjectProvider<ApprovalInterceptor> approvalInterceptors) {
        this.purchasingReferenceService = purchasingReferenceService;
        this.approvalService = approvalService;
        this.idempotency = idempotency;
        this.fingerprint = fingerprint;
        this.authorization = authorization;
        this.invoiceCases = invoiceCases;
        this.interceptor = approvalInterceptors.getIfAvailable(() -> ApprovalInterceptor.NONE);
    }

    /**
     * Runs with {@link Propagation#NOT_SUPPORTED} so a direct call from an
     * existing transaction suspends it: the external purchasing fetch below can
     * never run while the caller's transaction or row locks are active. The
     * write transaction is started afterwards by {@link ApprovalService}.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommandResult<ApprovalResult> approve(ApproveInvoiceCaseCommand command) {
        // Approval is an APPROVER action, enforced before any external call.
        authorization.requireRole(Role.APPROVER);
        Actor actor = authorization.actor();

        String requestHash = fingerprint.approve(command);
        Optional<RequestIdempotencyStore.StoredResponse> existing = idempotency.find(
                ApprovalService.SCOPE_APPROVE, command.caseId().toString(), actor.username(), command.requestId());
        if (existing.isPresent()) {
            RequestIdempotencyStore.StoredResponse stored = existing.get();
            if (!stored.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        ApprovalService.SCOPE_APPROVE, command.caseId().toString(), command.requestId());
            }
            return new CommandResult<>(stored.status(), idempotency.decode(stored, ApprovalResult.class));
        }

        InvoiceCase invoiceCase = invoiceCases
                .findById(command.caseId())
                .orElseThrow(() -> new InvoiceCaseNotFoundException(command.caseId()));

        // Fetch, validate and canonicalize the external aggregate without a
        // transaction or lock; the write transaction applies it atomically.
        PreparedPurchaseOrderSnapshot preparedSnapshot = purchasingReferenceService.fetchValidated(
                new RefreshPurchaseOrderCommand(
                        PurchaseOrderId.of(invoiceCase.purchaseOrder().value()),
                        SupplierId.of(invoiceCase.supplier().value())));
        interceptor.afterExternalFetch(command.caseId());

        return approvalService.approve(command, requestHash, preparedSnapshot);
    }
}
