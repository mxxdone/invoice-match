package com.invoicematch.core.analysis.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded analysis-relay configuration, kept with the infrastructure adapter
 * (mirroring the P1-08 payment relay). The switch defaults to {@code false}, so
 * no scheduler, no RabbitMQ connection and no broker health call happen until an
 * environment explicitly opts in.
 *
 * <p>The lease must comfortably exceed the bounded single-attempt deadline
 * (10s) so a live claim is never recovered while a publish is in flight. When
 * enabled, a blank host/username/password fails closed at startup. Credentials
 * come only from the environment and are never defaulted, logged or committed.
 */
@ConfigurationProperties(prefix = "analysis.relay")
public record AnalysisRelayProperties(
        boolean enabled,
        Duration leaseDuration,
        Duration retryDelay,
        int batchSize,
        Duration interval,
        Rabbit rabbit) {

    /** Hard upper bound on rows processed in one bounded tick. */
    public static final int MAX_TICK_BATCH = 10;

    /** Bound on the whole connect/declare/publish/confirm attempt. */
    public static final Duration ATTEMPT_DEADLINE = Duration.ofSeconds(10);

    public AnalysisRelayProperties {
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration;
        retryDelay = retryDelay == null ? Duration.ofSeconds(30) : retryDelay;
        interval = interval == null ? Duration.ofSeconds(5) : interval;
        batchSize = batchSize <= 0 ? MAX_TICK_BATCH : Math.min(batchSize, MAX_TICK_BATCH);
        rabbit = rabbit == null ? Rabbit.defaults() : rabbit;
        if (leaseDuration.compareTo(ATTEMPT_DEADLINE) <= 0) {
            throw new IllegalArgumentException(
                    "analysis.relay.lease-duration must exceed the attempt deadline " + ATTEMPT_DEADLINE);
        }
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("analysis.relay.retry-delay must not be negative");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("analysis.relay.interval must be positive");
        }
        if (enabled) {
            if (rabbit.host() == null || rabbit.host().isBlank()) {
                throw new IllegalArgumentException("analysis.relay.rabbit.host must be set when relay is enabled");
            }
            if (rabbit.username() == null || rabbit.username().isBlank()) {
                throw new IllegalArgumentException("analysis.relay.rabbit.username must be set when relay is enabled");
            }
            if (rabbit.password() == null || rabbit.password().isBlank()) {
                throw new IllegalArgumentException("analysis.relay.rabbit.password must be set when relay is enabled");
            }
        }
    }

    public record Rabbit(
            String host,
            int port,
            String vhost,
            String username,
            String password,
            String exchange,
            String queue,
            String routingKey) {

        private static final String DEFAULT_EXCHANGE = "invoice.analysis";
        private static final String DEFAULT_QUEUE = "invoice.analysis.requests";
        private static final String DEFAULT_ROUTING_KEY = "document-parser-v1";

        static Rabbit defaults() {
            return new Rabbit("localhost", 5672, "/", "", "", DEFAULT_EXCHANGE, DEFAULT_QUEUE, DEFAULT_ROUTING_KEY);
        }

        public Rabbit {
            host = (host == null || host.isBlank()) ? "localhost" : host;
            port = port <= 0 ? 5672 : port;
            vhost = (vhost == null || vhost.isBlank()) ? "/" : vhost;
            exchange = (exchange == null || exchange.isBlank()) ? DEFAULT_EXCHANGE : exchange;
            queue = (queue == null || queue.isBlank()) ? DEFAULT_QUEUE : queue;
            routingKey = (routingKey == null || routingKey.isBlank()) ? DEFAULT_ROUTING_KEY : routingKey;
        }
    }
}
