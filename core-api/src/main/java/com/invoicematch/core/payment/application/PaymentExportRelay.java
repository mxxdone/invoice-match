package com.invoicematch.core.payment.application;

import com.invoicematch.core.payment.adapter.PaymentExportClient;
import com.invoicematch.core.payment.adapter.PaymentExportOutcome;
import com.invoicematch.core.payment.adapter.PaymentExportProperties;
import com.invoicematch.core.payment.adapter.PaymentExportRequest;
import com.invoicematch.core.payment.persistence.ClaimedEvent;
import com.invoicematch.core.payment.persistence.OutboxStore;
import com.invoicematch.core.payment.persistence.SendingEvent;
import com.invoicematch.core.payment.persistence.StaleClaimException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1 in-process outbox relay. One tick recovers expired leases, claims a
 * bounded batch with a short DB-time transaction, and for each event commits
 * CLAIMED -> SENDING before the HTTP call. HTTP never runs inside a database
 * transaction or row lock.
 *
 * <p>A stale worker cannot finalize: every finalization is a claim-token
 * compare-and-set and a mismatch rolls its transaction back. Delivery is
 * at-least-once only for the explicit safe retry (429) path, and the business
 * identity (payment request + idempotency key) is stable across attempts.
 */
@Service
public class PaymentExportRelay {

    private static final Logger log = LoggerFactory.getLogger(PaymentExportRelay.class);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(5);

    private final OutboxStore store;
    private final PaymentExportClient client;
    private final PaymentExportProperties properties;
    private final PaymentExportInterceptor interceptor;
    private final Clock clock;
    private final String workerId = "worker-" + UUID.randomUUID();

    public PaymentExportRelay(
            OutboxStore store,
            PaymentExportClient client,
            PaymentExportProperties properties,
            ObjectProvider<PaymentExportInterceptor> interceptors,
            Clock clock) {
        this.store = store;
        this.client = client;
        this.properties = properties;
        this.interceptor = interceptors.getIfAvailable(() -> PaymentExportInterceptor.NONE);
        this.clock = clock;
    }

    public String workerId() {
        return workerId;
    }

    /**
     * Runs one bounded relay tick and returns the number of acknowledgements.
     *
     * <p>{@link Propagation#NEVER} rejects a caller that already holds a
     * transaction before any HTTP or DB effect, so the relay can never run under
     * a caller's transaction or locks. Each internal step is its own short
     * transaction and HTTP runs outside every transaction.
     */
    @Transactional(propagation = Propagation.NEVER)
    public int runOnce() {
        PaymentExportProperties.Relay relay = properties.relay();
        int recovered = store.recoverExpired();
        if (recovered > 0) {
            log.info("recovered {} expired payment export lease(s)", recovered);
        }
        List<ClaimedEvent> claimed = store.claimBatch(relay.batchSize(), workerId, relay.leaseDuration());
        int acknowledged = 0;
        for (ClaimedEvent event : claimed) {
            interceptor.afterClaimed(event.eventId());
            if (process(event, relay)) {
                acknowledged++;
            }
        }
        return acknowledged;
    }

    private boolean process(ClaimedEvent event, PaymentExportProperties.Relay relay) {
        Optional<SendingEvent> sending;
        try {
            sending = store.beginSending(event.eventId(), event.claimToken(), workerId);
        } catch (StaleClaimException e) {
            log.debug("skipping stale claim {}", event.eventId());
            return false;
        }
        if (sending.isEmpty()) {
            return false;
        }
        SendingEvent claimed = sending.get();
        interceptor.afterSendingCommitted(claimed.eventId());

        PaymentExportOutcome outcome = client.send(new PaymentExportRequest(
                claimed.paymentRequestId(), claimed.idempotencyKey(), claimed.payload()));
        interceptor.beforeFinalize(claimed.eventId(), outcome);

        try {
            switch (outcome) {
                case PaymentExportOutcome.Acknowledged acknowledged ->
                        store.finalizeSuccess(claimed, acknowledged.httpStatus(), clock.instant(), workerId);
                case PaymentExportOutcome.NonRetryable nonRetryable ->
                        store.finalizeFailure(claimed, nonRetryable.httpStatus(), nonRetryable.errorCode(),
                                nonRetryable.detail(), workerId);
                case PaymentExportOutcome.RateLimited rateLimited -> handleRateLimited(claimed, rateLimited, relay);
                case PaymentExportOutcome.ResultUnknown unknown ->
                        store.markResultUnknown(claimed, unknown.httpStatus(), unknown.errorCode(),
                                unknown.detail(), workerId);
            }
        } catch (StaleClaimException e) {
            log.warn("lost the claim on {} before finalization; the state is unchanged", claimed.eventId());
            return false;
        }
        interceptor.afterFinalized(claimed.eventId());
        return outcome instanceof PaymentExportOutcome.Acknowledged;
    }

    private void handleRateLimited(
            SendingEvent event, PaymentExportOutcome.RateLimited rateLimited, PaymentExportProperties.Relay relay) {
        if (event.attemptNumber() >= relay.maxAttempts()) {
            store.finalizeFailure(event, rateLimited.httpStatus(), "RATE_LIMIT_EXHAUSTED",
                    "429 exhausted after " + event.attemptNumber() + " attempt(s)", workerId);
            return;
        }
        Duration backoff = rateLimited.retryAfter() != null ? rateLimited.retryAfter() : relay.retryBackoff();
        if (backoff.isNegative() || backoff.isZero()) {
            backoff = relay.retryBackoff();
        }
        if (backoff.compareTo(MAX_RETRY_BACKOFF) > 0) {
            backoff = MAX_RETRY_BACKOFF;
        }
        store.scheduleRetry(event, rateLimited.httpStatus(), backoff, rateLimited.detail(), workerId);
    }
}
