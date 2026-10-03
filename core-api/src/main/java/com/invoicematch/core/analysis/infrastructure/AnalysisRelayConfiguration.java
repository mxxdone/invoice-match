package com.invoicematch.core.analysis.infrastructure;

import com.invoicematch.core.analysis.application.AnalysisRelayProperties;
import com.invoicematch.core.analysis.application.AnalysisRequestPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the analysis-request publisher port. The RabbitMQ adapter
 * (and therefore any SDK connection) exists only when the relay is explicitly
 * enabled; otherwise a fail-closed adapter is bound so the application layer
 * still has a port but no broker I/O can happen.
 */
@Configuration
@EnableConfigurationProperties(AnalysisRelayProperties.class)
public class AnalysisRelayConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "analysis.relay", name = "enabled", havingValue = "true")
    AnalysisRequestPublisher rabbitAnalysisRequestPublisher(AnalysisRelayProperties properties) {
        return new RabbitAnalysisRequestPublisher(properties);
    }

    @Bean
    @ConditionalOnMissingBean(AnalysisRequestPublisher.class)
    AnalysisRequestPublisher disabledAnalysisRequestPublisher() {
        return new DisabledAnalysisRequestPublisher();
    }
}
