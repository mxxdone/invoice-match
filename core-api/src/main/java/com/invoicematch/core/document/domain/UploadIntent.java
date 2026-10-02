package com.invoicematch.core.document.domain;

import java.time.Instant;
import java.util.UUID;

public record UploadIntent(UUID id, UUID caseId, UUID draftRevisionId, String fileName,
        String mediaType, long sizeBytes, String checksum, String uploadKey, Instant expiresAt, Instant createdAt) {
}
