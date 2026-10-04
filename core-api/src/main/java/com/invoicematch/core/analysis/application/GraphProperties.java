package com.invoicematch.core.analysis.application;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.graph")
public record GraphProperties(boolean enabled, Duration leaseDuration, BigDecimal costCeiling) {
    public GraphProperties {
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(120) : leaseDuration;
        if (leaseDuration.compareTo(Duration.ofSeconds(75)) < 0 || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Graph lease must be 75 seconds to 5 minutes");
        if (enabled && (costCeiling == null || costCeiling.signum() <= 0 || costCeiling.compareTo(new BigDecimal("1000000")) > 0))
            throw new IllegalArgumentException("An explicit positive graph cost ceiling is required");
    }
}
