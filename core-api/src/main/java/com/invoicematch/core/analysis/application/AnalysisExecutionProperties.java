package com.invoicematch.core.analysis.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Execution lease policy. The lease is the bounded time a machine worker may
 * hold a RUNNING claim before another claim may reclaim the run. The deadline
 * itself is always computed by the database clock.
 */
@ConfigurationProperties(prefix = "analysis.execution")
public record AnalysisExecutionProperties(Duration leaseDuration) {

    public AnalysisExecutionProperties {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("analysis.execution.lease-duration must be positive");
        }
    }
}
