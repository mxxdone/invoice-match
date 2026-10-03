package com.invoicematch.core.analysis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Machine-worker API switch. Disabled by default; when enabled a non-empty
 * {@code ANALYSIS_WORKER_TOKEN} of at least 32 characters is required and is
 * compared in constant time. The token is never logged, defaulted or persisted.
 */
@ConfigurationProperties(prefix = "analysis.worker")
public record AnalysisWorkerProperties(boolean enabled, String token) {

    /** Minimum accepted secret length; shorter tokens fail closed. */
    public static final int MIN_TOKEN_LENGTH = 32;
}
