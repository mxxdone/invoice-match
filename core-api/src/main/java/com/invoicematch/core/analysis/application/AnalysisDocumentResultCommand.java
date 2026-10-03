package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Machine result submission for one manifest document. {@code result} is the raw
 * P2-04 {@code document-parse-v1} wire object for a success and null for a
 * failure; it is never re-parsed by Java, only structurally validated and
 * canonically hashed.
 */
public record AnalysisDocumentResultCommand(
        UUID claimToken,
        Integer inputVersion,
        String evidencePayloadHash,
        UUID documentId,
        String outcome,
        JsonNode result,
        String errorCode) {
}
