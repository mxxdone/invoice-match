package com.invoicematch.core.purchasingreference.adapter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the external purchasing system. Defaults target the
 * local mock-purchasing service.
 */
@ConfigurationProperties(prefix = "purchasing-system")
public record PurchasingSystemProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public PurchasingSystemProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8082" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
