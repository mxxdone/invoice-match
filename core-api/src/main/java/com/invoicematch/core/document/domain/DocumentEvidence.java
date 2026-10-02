package com.invoicematch.core.document.domain;

import java.util.UUID;

/**
 * Immutable metadata of a completed document as frozen into an evidence bundle.
 * The document id and its original (source) draft revision are stable identity;
 * the name, media type, size and checksum are the verified content facts. Object
 * keys and upload capabilities are deliberately not part of this value.
 */
public record DocumentEvidence(
        UUID documentId,
        UUID sourceDraftRevisionId,
        String fileName,
        String mediaType,
        long sizeBytes,
        String checksum) {
}
