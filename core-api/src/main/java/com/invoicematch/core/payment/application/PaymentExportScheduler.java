package com.invoicematch.core.payment.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded, configurable relay tick. The scheduler is controllable per profile;
 * integration tests disable it and drive {@link PaymentExportRelay#runOnce()}
 * deterministically. A duplicate or overlapping execution is harmless because
 * claiming uses SKIP LOCKED and the lease/token guards make finalization
 * idempotent-safe (a second worker cannot mutably re-finalize).
 */
@Component
@ConditionalOnProperty(prefix = "payment-export.relay", name = "enabled", havingValue = "true")
public class PaymentExportScheduler {

    private final PaymentExportRelay relay;

    public PaymentExportScheduler(PaymentExportRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${payment-export.relay.interval:2s}")
    public void tick() {
        relay.runOnce();
    }
}
