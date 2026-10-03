package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.persistence.AnalysisOutboxStore;
import com.invoicematch.core.analysis.persistence.ClaimedAnalysisRequest;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application coordination of the analysis-request relay. One tick recovers
 * expired leases/stale reservations, then claims and publishes one due request
 * at a time (never pre-claiming the whole batch), and finalizes/releases with a
 * claim-token conditional update.
 *
 * <p>{@link Propagation#NEVER} rejects a caller that already holds a transaction
 * before any DB or broker effect, and every store call is its own short
 * transaction, so the broker is never called inside a transaction or row lock.
 * On an ambiguous outcome the request is released for a later attempt with the
 * same event id; the relay does not claim exactly-once delivery.
 */
@Service
public class AnalysisRequestRelay {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRequestRelay.class);

    private final AnalysisOutboxStore store;
    private final AnalysisRequestPublisher publisher;
    private final AnalysisRelayProperties properties;
    private final String workerId = "analysis-relay-" + UUID.randomUUID();

    public AnalysisRequestRelay(
            AnalysisOutboxStore store, AnalysisRequestPublisher publisher, AnalysisRelayProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
    }

    public String workerId() {
        return workerId;
    }

    /**
     * Runs one bounded tick and returns the number of requests finalized as
     * PUBLISHED. Recovers first, then claims/publishes one request at a time up
     * to the configured batch size.
     */
    @Transactional(propagation = Propagation.NEVER)
    public int runOnce() {
        int recovered = store.recoverExpired();
        if (recovered > 0) {
            log.info("recovered {} expired analysis request lease(s)", recovered);
        }
        int published = 0;
        for (int processed = 0; processed < properties.batchSize(); processed++) {
            Optional<ClaimedAnalysisRequest> claimed =
                    store.claimOne(workerId, properties.leaseDuration());
            if (claimed.isEmpty()) {
                break;
            }
            if (publish(claimed.get())) {
                published++;
            }
        }
        return published;
    }

    private boolean publish(ClaimedAnalysisRequest claimed) {
        AnalysisPublishResult result;
        try {
            result = publisher.publish(new AnalysisPublishCommand(claimed.eventId(), claimed.payload()));
        } catch (RuntimeException e) {
            // The adapter must classify failures, but never let an unexpected
            // throw escape and lose the claim: release it instead.
            log.warn("analysis request {} publish threw; releasing", claimed.eventId());
            store.releaseForRetry(
                    claimed.eventId(), claimed.claimToken(),
                    AnalysisPublishError.IO_FAILED.name(), properties.retryDelay());
            return false;
        }
        if (result instanceof AnalysisPublishResult.Published) {
            boolean finalized = store.finalizePublished(claimed.eventId(), claimed.claimToken());
            if (!finalized) {
                log.warn("analysis request {} lost its claim before finalization", claimed.eventId());
            }
            return finalized;
        }
        AnalysisPublishError error = ((AnalysisPublishResult.Failed) result).error();
        log.warn("analysis request {} publish failed: {}", claimed.eventId(), error.name());
        store.releaseForRetry(claimed.eventId(), claimed.claimToken(), error.name(), properties.retryDelay());
        return false;
    }
}
