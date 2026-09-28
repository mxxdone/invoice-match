package com.invoicematch.core.payment.adapter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded, validated relay configuration.
 *
 * <p>{@code requestTimeout} is a real per-request total deadline applied to the
 * JDK HttpClient, bounding connect + headers + body + completion. The sending
 * lease must be strictly longer than {@code requestTimeout + safetyMargin} so a
 * lease can never expire while a request is still bounded as in-flight, and the
 * safety margin must be positive. Lease comparisons use DB time, so JVM clock
 * skew between nodes cannot affect them.
 *
 * <p>The relay scheduler is fail-closed: {@code enabled} defaults to false and
 * must be explicitly opted in only when an idempotent ERP receiver (P1-09) is
 * deployed, because a health-only endpoint would turn approvals into definite
 * 404 FAILED states.
 */
@ConfigurationProperties(prefix = "payment-export")
public record PaymentExportProperties(Relay relay) {

    public record Relay(
            boolean enabled,
            String baseUrl,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration leaseDuration,
            Duration safetyMargin,
            int batchSize,
            Duration interval,
            int maxAttempts,
            Duration retryBackoff) {

        public Relay {
            baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8081" : baseUrl;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
            leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
            safetyMargin = safetyMargin == null ? Duration.ofSeconds(5) : safetyMargin;
            interval = interval == null ? Duration.ofSeconds(2) : interval;
            retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
            batchSize = batchSize <= 0 ? 20 : Math.min(batchSize, 100);
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            if (connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalArgumentException("payment-export.relay.connect-timeout must be positive");
            }
            if (requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("payment-export.relay.request-timeout must be positive");
            }
            if (safetyMargin.isZero() || safetyMargin.isNegative()) {
                throw new IllegalArgumentException("payment-export.relay.safety-margin must be positive");
            }
            Duration requiredLease = requestTimeout.plus(safetyMargin);
            if (leaseDuration.compareTo(requiredLease) < 0) {
                throw new IllegalArgumentException(
                        "payment-export.relay.lease-duration must be at least request-timeout + safety-margin ("
                                + requiredLease + ")");
            }
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("payment-export.relay.interval must be positive");
            }
            if (retryBackoff.isNegative()) {
                throw new IllegalArgumentException("payment-export.relay.retry-backoff must not be negative");
            }
        }
    }
}
