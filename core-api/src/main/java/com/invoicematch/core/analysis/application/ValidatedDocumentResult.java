package com.invoicematch.core.analysis.application;

/**
 * A structurally valid, canonically hashed document result ready to store.
 * {@code payloadJson} is the canonical result object for a success and null for
 * a failure. The stored {@code payloadHash} covers outcome, source checksum,
 * schema/parser identity and the result/error only.
 */
public record ValidatedDocumentResult(
        String outcome,
        String parserVersion,
        String resultSchemaVersion,
        String payloadJson,
        String errorCode,
        String payloadHash) {
}
