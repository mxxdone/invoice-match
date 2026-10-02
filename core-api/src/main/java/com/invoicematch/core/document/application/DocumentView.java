package com.invoicematch.core.document.application;

import com.invoicematch.core.document.domain.RegisteredDocument;
import java.time.Instant;
import java.util.UUID;

public record DocumentView(UUID documentId, UUID draftRevisionId, String fileName, String mediaType,
        long sizeBytes, String checksum, long caseVersion, Instant registeredAt) {
    public static DocumentView from(RegisteredDocument d) {
        var u = d.upload();
        return new DocumentView(u.id(), u.draftRevisionId(), u.fileName(), u.mediaType(), u.sizeBytes(),
                u.checksum(), d.caseVersion(), d.registeredAt());
    }
}
