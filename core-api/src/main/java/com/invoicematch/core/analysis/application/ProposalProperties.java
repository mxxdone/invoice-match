package com.invoicematch.core.analysis.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.ai")
public record ProposalProperties(boolean enabled, Duration leaseDuration) {
    public ProposalProperties {
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(120) : leaseDuration;
        if (leaseDuration.compareTo(Duration.ofSeconds(75)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("analysis.ai.lease-duration must be 75 seconds to 5 minutes");
    }
}
