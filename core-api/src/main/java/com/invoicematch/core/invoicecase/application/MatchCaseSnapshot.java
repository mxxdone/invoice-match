package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import java.util.UUID;

/**
 * Immutable read model handed to the matching module: the case header identity,
 * its current review-relevant status/version and the latest frozen evidence
 * bundle version including the canonical payload the matching engine compares.
 *
 * <p>It deliberately exposes identifiers and values only, so the matching
 * package never shares the invoice case JPA entities.
 */
public record MatchCaseSnapshot(
        UUID caseId,
        String supplierId,
        String purchaseOrderId,
        String invoiceNumber,
        String normalizedInvoiceNumber,
        InvoiceCaseStatus status,
        long caseVersion,
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        String evidenceBundleHash,
        String evidenceBundlePayload) {
}
