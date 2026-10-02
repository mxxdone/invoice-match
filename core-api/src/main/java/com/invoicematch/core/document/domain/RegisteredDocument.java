package com.invoicematch.core.document.domain;

import java.time.Instant;

public record RegisteredDocument(UploadIntent upload, String objectKey, long caseVersion, Instant registeredAt) {
}
