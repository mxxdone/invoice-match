package com.invoicematch.core.analysis.persistence;

import java.util.UUID;

/**
 * One immutable per-document result already stored for a run. {@code payloadHash}
 * is the canonical hash compared on replay; {@code outcome} drives the terminal
 * decision when the last manifest document is stored.
 */
public record StoredDocumentResult(UUID documentId, String outcome, String payloadHash, String errorCode) {
}
