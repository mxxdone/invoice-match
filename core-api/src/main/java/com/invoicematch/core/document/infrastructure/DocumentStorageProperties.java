package com.invoicematch.core.document.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("document-storage")
public record DocumentStorageProperties(boolean enabled, String endpoint, String publicEndpoint,
        String accessKey, String secretKey, String bucket, String region) {
}
