package com.invoicematch.core.analysis.application;

import com.invoicematch.core.document.domain.DocumentEvidence;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The frozen input identity a submission hands to the analysis application
 * layer: the case, the frozen evidence bundle/version and its payload hash,
 * plus the immutable document metadata frozen into that bundle. It carries no
 * original bytes, object keys, URLs or credentials.
 */
public record AnalysisInput(
        UUID invoiceCaseId,
        UUID evidenceBundleId,
        int inputVersion,
        String evidencePayloadHash,
        List<DocumentEvidence> documents) {

    public AnalysisInput {
        Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        Objects.requireNonNull(evidencePayloadHash, "evidencePayloadHash");
        Objects.requireNonNull(documents, "documents");
        documents = List.copyOf(documents);
    }
}
