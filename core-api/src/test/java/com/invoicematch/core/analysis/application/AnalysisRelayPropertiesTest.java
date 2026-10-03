package com.invoicematch.core.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Bounds, fail-closed credentials and batch clamping of the relay properties. */
class AnalysisRelayPropertiesTest {

    @Test
    void appliesSafeDefaultsWhenFieldsAreMissing() {
        AnalysisRelayProperties properties =
                new AnalysisRelayProperties(false, null, null, 0, null, null);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.retryDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.batchSize()).isEqualTo(AnalysisRelayProperties.MAX_TICK_BATCH);
        assertThat(properties.rabbit().host()).isEqualTo("localhost");
        assertThat(properties.rabbit().password()).isEmpty();
    }

    @Test
    void clampsBatchAndRejectsALeaseShorterThanTheAttemptDeadline() {
        AnalysisRelayProperties clamped =
                new AnalysisRelayProperties(false, Duration.ofSeconds(60), Duration.ofSeconds(30), 99, null, null);
        assertThat(clamped.batchSize()).isEqualTo(AnalysisRelayProperties.MAX_TICK_BATCH);
        assertThatThrownBy(() ->
                new AnalysisRelayProperties(false, Duration.ofSeconds(5), Duration.ofSeconds(30), 10, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
    }

    @Test
    void enabledRelayFailsClosedWithoutCredentials() {
        assertThatThrownBy(() -> new AnalysisRelayProperties(
                        true, Duration.ofSeconds(60), Duration.ofSeconds(30), 10, Duration.ofSeconds(5),
                        new AnalysisRelayProperties.Rabbit("localhost", 5672, "/", "", "", "ex", "q", "rk")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
        assertThatThrownBy(() -> new AnalysisRelayProperties(
                        true, Duration.ofSeconds(60), Duration.ofSeconds(30), 10, Duration.ofSeconds(5),
                        new AnalysisRelayProperties.Rabbit("localhost", 5672, "/", "u", "", "ex", "q", "rk")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }
}
