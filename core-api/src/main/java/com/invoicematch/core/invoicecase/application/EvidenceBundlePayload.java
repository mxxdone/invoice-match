package com.invoicematch.core.invoicecase.application;

import java.util.List;

/**
 * Typed view of the canonical JSON stored in an evidence bundle. The case
 * header fields are immutable identity; the revision number records which draft
 * revision was frozen; the lines are the claim content at submission time.
 *
 * <p>A document-less legacy payload has no {@code schemaVersion} or
 * {@code documents} member, so both deserialize as {@code null}. A payload that
 * froze completed documents carries {@code schemaVersion = 2} and the ordered
 * document metadata; the object key, upload URL and credentials are never part
 * of the payload.
 */
public record EvidenceBundlePayload(
        Integer schemaVersion,
        String caseId,
        String supplierId,
        String purchaseOrderId,
        String invoiceNumber,
        int revisionNumber,
        List<EvidenceLine> lines,
        List<DocumentLine> documents) {

    public record EvidenceLine(
            int lineNumber, String rawItemName, int quantity, long unitPrice, String confirmedItemId) {
    }

    public record DocumentLine(
            String documentId,
            String sourceDraftRevisionId,
            String fileName,
            String mediaType,
            long sizeBytes,
            String checksum) {
    }
}
