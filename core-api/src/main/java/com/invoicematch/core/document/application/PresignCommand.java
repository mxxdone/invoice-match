package com.invoicematch.core.document.application;

import java.util.UUID;

public record PresignCommand(UUID caseId, String requestId, Long expectedCaseVersion, UUID draftRevisionId,
        String fileName, String mediaType, Long sizeBytes, String checksum) {
    public void validate() {
        DocumentPolicy.request(requestId, expectedCaseVersion);
        DocumentPolicy.metadata(fileName, mediaType, sizeBytes, checksum);
        if (caseId == null || draftRevisionId == null) {
            throw new com.invoicematch.core.shared.domain.DomainValidationException("caseId and draftRevisionId are required");
        }
    }
}
