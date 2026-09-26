package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.purchasingreference.application.PreparedPurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand;
import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Orchestrates invoice case commands. For creation it validates the external
 * purchase order through the P1-02 purchasing reference contract before opening
 * any database transaction, so the external HTTP call is never made while a
 * transaction or row lock is held. Replay detection happens first so a retry
 * never triggers another external call.
 */
@Service
public class InvoiceCaseApplicationService {

    private final PurchasingReferenceService purchasingReferenceService;
    private final InvoiceCaseWriteService writeService;
    private final RequestIdempotencyStore idempotency;
    private final RequestFingerprint fingerprint;

    public InvoiceCaseApplicationService(
            PurchasingReferenceService purchasingReferenceService,
            InvoiceCaseWriteService writeService,
            RequestIdempotencyStore idempotency,
            RequestFingerprint fingerprint) {
        this.purchasingReferenceService = purchasingReferenceService;
        this.writeService = writeService;
        this.idempotency = idempotency;
        this.fingerprint = fingerprint;
    }

    public CommandResult<InvoiceCaseDetail> create(CreateInvoiceCaseCommand command) {
        // Pure input validation before anything observable happens: a supplier
        // invoice number that normalizes to nothing is an input error, not an
        // external lookup.
        requireNormalizableInvoiceNumber(command.invoiceNumber());

        String requestHash = fingerprint.create(command);
        Optional<RequestIdempotencyStore.StoredResponse> existing = idempotency.find(
                InvoiceCaseWriteService.SCOPE_CREATE, InvoiceCaseWriteService.CREATE_RESOURCE_KEY, command.requestId());
        if (existing.isPresent()) {
            RequestIdempotencyStore.StoredResponse stored = existing.get();
            if (!stored.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        InvoiceCaseWriteService.SCOPE_CREATE, InvoiceCaseWriteService.CREATE_RESOURCE_KEY, command.requestId());
            }
            return new CommandResult<>(stored.status(), idempotency.decode(stored, InvoiceCaseDetail.class));
        }

        // Fetch, validate and canonicalize the external aggregate without any
        // database transaction or lock. The write transaction applies the
        // prepared snapshot only after it wins the request-id reservation.
        PreparedPurchaseOrderSnapshot preparedSnapshot = purchasingReferenceService.fetchValidated(
                new RefreshPurchaseOrderCommand(
                        PurchaseOrderId.of(command.purchaseOrderId()), SupplierId.of(command.supplierId())));
        return writeService.create(command, requestHash, preparedSnapshot);
    }

    private static void requireNormalizableInvoiceNumber(String invoiceNumber) {
        String normalized = InvoiceNumberNormalizer.normalize(invoiceNumber);
        if (normalized == null || normalized.isBlank()) {
            throw new DomainValidationException(
                    "invoiceNumber must contain at least one letter or digit");
        }
    }

    public CommandResult<InvoiceCaseDetail> replaceDraft(ReplaceDraftLinesCommand command) {
        return writeService.replaceDraft(command);
    }

    public CommandResult<SubmissionResult> submit(SubmitInvoiceCaseCommand command) {
        return writeService.submit(command);
    }

    public CommandResult<InvoiceCaseDetail> openSupplementRevision(OpenSupplementRevisionCommand command) {
        return writeService.openSupplementRevision(command);
    }
}
