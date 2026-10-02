package com.invoicematch.core.document.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PresignView(UUID documentId, UUID draftRevisionId, long caseVersion, String uploadUrl,
        String method, Map<String, String> requiredHeaders, Instant expiresAt) {
}
