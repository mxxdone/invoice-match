package com.invoicematch.core.analysis.infrastructure;

import com.invoicematch.core.analysis.application.AnalysisRequestRelay;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opt-in infrastructure entry point for the relay. It is only created when
 * {@code analysis.relay.enabled=true}, so a disabled relay never schedules and
 * never opens an SDK connection. A non-blocking guard prevents overlapping ticks
 * on the same instance; deterministic integration tests call the relay directly
 * instead of relying on the scheduler.
 */
@Component
@ConditionalOnProperty(prefix = "analysis.relay", name = "enabled", havingValue = "true")
public class AnalysisRelayScheduler {

    private final AnalysisRequestRelay relay;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AnalysisRelayScheduler(AnalysisRequestRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${analysis.relay.interval:5s}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            relay.runOnce();
        } finally {
            running.set(false);
        }
    }
}
