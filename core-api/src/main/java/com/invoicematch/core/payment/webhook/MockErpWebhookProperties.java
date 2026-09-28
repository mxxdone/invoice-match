package com.invoicematch.core.payment.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signed mock-ERP result webhook contract.
 *
 * <p>The shared secret is never logged or persisted; it is only used to verify
 * the HMAC over {@code "<timestamp>.<raw body>"}. A blank secret fails closed
 * (every request is rejected) so a misconfigured deployment cannot accept
 * unsigned results. The timestamp tolerance bounds replay of a captured signed
 * request.
 */
@ConfigurationProperties(prefix = "mock-erp.webhook")
public record MockErpWebhookProperties(String provider, String secret, Duration signatureTolerance) {

    public static final String DEFAULT_PROVIDER = "mock-erp";

    public MockErpWebhookProperties {
        provider = (provider == null || provider.isBlank()) ? DEFAULT_PROVIDER : provider;
        secret = secret == null ? "" : secret;
        signatureTolerance = signatureTolerance == null ? Duration.ofMinutes(5) : signatureTolerance;
        if (signatureTolerance.isZero() || signatureTolerance.isNegative()) {
            throw new IllegalArgumentException("mock-erp.webhook.signature-tolerance must be positive");
        }
    }

    /** Whether a signing secret is configured at all. */
    public boolean hasSecret() {
        return !secret.isBlank();
    }
}
