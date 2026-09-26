package com.invoicematch.core.invoicecase.application;

import java.util.List;

/**
 * Typed view of the canonical JSON stored in an evidence bundle. The case
 * header fields are immutable identity; the revision number records which draft
 * revision was frozen; the lines are the claim content at submission time.
 */
public record EvidenceBundlePayload(
        String caseId,
        String supplierId,
        String purchaseOrderId,
        String invoiceNumber,
        int revisionNumber,
        List<EvidenceLine> lines) {

    public record EvidenceLine(
            int lineNumber, String rawItemName, int quantity, long unitPrice, String confirmedItemId) {
    }
}
