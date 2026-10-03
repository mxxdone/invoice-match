package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.AnalysisRun;
import com.invoicematch.core.document.domain.DocumentEvidence;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the canonical analysis-request payload from the immutable run and its
 * frozen document metadata. Key order is fixed and documents are sorted by
 * document id, so the same frozen bundle always produces byte-identical JSON.
 * The payload never contains original bytes, object keys, URLs, credentials or
 * execution timestamps.
 */
@Component
public class AnalysisRequestPayloadFactory {

    private static final Comparator<DocumentEvidence> DOCUMENT_ORDER =
            Comparator.comparing(document -> document.documentId().toString());

    private final ObjectMapper mapper = new ObjectMapper();

    public String canonicalPayload(UUID eventId, AnalysisRun run, List<DocumentEvidence> documents) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("analysisRunId", run.id().toString());
        root.put("invoiceCaseId", run.invoiceCaseId().toString());
        root.put("evidenceBundleId", run.evidenceBundleId().toString());
        root.put("inputVersion", run.inputVersion());
        root.put("evidencePayloadHash", run.evidencePayloadHash());
        root.put("workflowVersion", run.workflowVersion());

        ArrayNode documentArray = root.putArray("documents");
        documents.stream().sorted(DOCUMENT_ORDER).forEach(document -> {
            ObjectNode node = documentArray.addObject();
            node.put("documentId", document.documentId().toString());
            node.put("sourceDraftRevisionId", document.sourceDraftRevisionId().toString());
            node.put("fileName", document.fileName());
            node.put("mediaType", document.mediaType());
            node.put("sizeBytes", document.sizeBytes());
            node.put("checksum", document.checksum());
        });

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("analysis request payload serialization failed", e);
        }
    }
}
