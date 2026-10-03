package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.analysis.application.AnalysisRequestRelay;
import com.invoicematch.core.analysis.infrastructure.AnalysisRelayScheduler;
import com.invoicematch.core.analysis.infrastructure.RabbitAnalysisRequestPublisher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * P2-06 fail-closed default ({@code analysis.relay.enabled=false}): no scheduler
 * bean, no RabbitMQ adapter bean (so no SDK connection or broker health call),
 * and even a direct relay invocation only fails closed and leaves the request
 * READY.
 */
class AnalysisRelayDisabledIntegrationTest extends AbstractAnalysisRelayIntegrationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    AnalysisRequestRelay relay;

    @Test
    void disabledRelayHasNoSchedulerAndNoRabbitAdapter() {
        assertThat(applicationContext.getBeanNamesForType(AnalysisRelayScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(RabbitAnalysisRequestPublisher.class)).isEmpty();
    }

    @Test
    void disabledRelayFailsClosedWithoutChangingTheReservation() {
        UUID caseId = createDraftCase("INV-DIS");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-dis");

        assertThat(relay.runOnce()).isZero();
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("READY");
        assertThat(outboxRow(caseId, 1).get("last_error_code")).isEqualTo("RELAY_DISABLED");
    }
}
