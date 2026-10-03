package com.invoicematch.core.analysis.domain;

/**
 * Fixed identifiers of the analysis workflow contract introduced in P2-05. The
 * workflow version is persisted on every run and is immutable; the request
 * schema version is the Outbox discriminator. Consumers in P2-06 must not
 * silently reinterpret a persisted value.
 */
public final class AnalysisWorkflow {

    /** Parser-backed analysis workflow frozen for this phase. */
    public static final String VERSION = "document-parser-v1";

    /** Persisted schema/discriminator of an analysis-request Outbox row. */
    public static final String REQUEST_SCHEMA_VERSION = "analysis-request-v1";

    private AnalysisWorkflow() {
    }
}
