package com.invoicematch.core.analysis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-closed analysis reservation switch. It defaults to {@code false}, so no
 * AnalysisRun or analysis-request Outbox row is reserved until an environment
 * explicitly opts in. This only gates new reservations; staleing lower evidence
 * versions still runs without it.
 */
@ConfigurationProperties(prefix = "analysis.request")
public record AnalysisRequestProperties(boolean enabled) {
}
