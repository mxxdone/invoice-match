package com.invoicematch.core.payment.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The lease must exceed the real per-request total deadline plus a positive
 * safety margin, not merely each timeout individually. Invalid combinations must
 * fail startup rather than allow a lease to expire mid-request.
 */
class PaymentExportPropertiesTest {

    private static PaymentExportProperties.Relay relay(
            Duration connect, Duration request, Duration lease, Duration margin) {
        return new PaymentExportProperties.Relay(
                false, "http://localhost:8081", connect, request, lease, margin, 20,
                Duration.ofSeconds(2), 3, Duration.ofSeconds(5));
    }

    @Test
    void acceptsLeaseAboveRequestDeadlineAndSafetyMargin() {
        PaymentExportProperties.Relay valid = relay(
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(5));
        assertThat(valid.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(valid.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void rejectsLeaseShorterThanRequestPlusMargin() {
        // request + margin = 8s, but the lease is only 3s.
        assertThatThrownBy(() -> relay(
                        Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
    }

    @Test
    void rejectsZeroOrNegativeSafetyMargin() {
        assertThatThrownBy(() -> relay(
                        Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safety-margin");
        assertThatThrownBy(() -> relay(
                        Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safety-margin");
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> relay(
                        Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relay(
                        Duration.ofSeconds(1), Duration.ofSeconds(-1), Duration.ofSeconds(30), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
