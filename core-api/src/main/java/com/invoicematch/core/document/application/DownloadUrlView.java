package com.invoicematch.core.document.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Browser-facing capability for a registered immutable original. The signed
 * URL necessarily carries the object path, but the object key and any
 * credential are never exposed as separate fields.
 */
public record DownloadUrlView(UUID documentId, String fileName, String mediaType, String url, String method,
        Instant expiresAt) {
}
