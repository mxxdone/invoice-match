package com.invoicematch.core.payment.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the mock-ERP webhook contract and fail-closed defaults. */
@Configuration
@EnableConfigurationProperties(MockErpWebhookProperties.class)
public class MockErpWebhookConfiguration {
}
