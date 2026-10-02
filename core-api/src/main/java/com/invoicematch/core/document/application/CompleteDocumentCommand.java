package com.invoicematch.core.document.application;

import java.util.UUID;

public record CompleteDocumentCommand(UUID caseId, String requestId, Long expectedCaseVersion,
        UUID draftRevisionId, UUID documentId, String checksum) {
    public void validate() {
        DocumentPolicy.request(requestId, expectedCaseVersion);
        DocumentPolicy.checksum(checksum);
        if (caseId == null || draftRevisionId == null || documentId == null) {
            throw new com.invoicematch.core.shared.domain.DomainValidationException("caseId, draftRevisionId and documentId are required");
        }
    }
}
