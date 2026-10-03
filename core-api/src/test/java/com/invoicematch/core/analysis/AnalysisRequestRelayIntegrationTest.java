package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.analysis.application.AnalysisPublishCommand;
import com.invoicematch.core.analysis.application.AnalysisPublishError;
import com.invoicematch.core.analysis.application.AnalysisPublishResult;
import com.invoicematch.core.analysis.application.AnalysisRequestPublisher;
import com.invoicematch.core.analysis.application.AnalysisRequestRelay;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P2-06 relay coordination against real PostgreSQL with a controlled publisher
 * port: publish only outside any transaction, finalize to PUBLISHED on success,
 * release to READY with a fixed classification code on failure, and re-publish
 * the same event id after an ambiguous crash. Delivery is at-least-once only;
 * the tests never assert exactly-once.
 */
class AnalysisRequestRelayIntegrationTest extends AbstractAnalysisRelayIntegrationTest {

    private static final RecordingPublisher PUBLISHER = new RecordingPublisher();

    @TestConfiguration
    static class RecordingPublisherConfiguration {
        @Bean
        @Primary
        AnalysisRequestPublisher recordingPublisher() {
            return PUBLISHER;
        }
    }

    @Autowired
    AnalysisRequestRelay relay;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetPublisher() {
        PUBLISHER.reset();
    }

    @Test
    void publishRunsOutsideAnyTransactionAndFinalizesToPublished() {
        UUID caseId = createDraftCase("INV-R1");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-r1");
        UUID eventId = outboxId(caseId, 1);

        assertThat(relay.runOnce()).isEqualTo(1);

        Map<String, Object> row = outboxRow(caseId, 1);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("published_at")).isNotNull();
        assertThat(PUBLISHER.commands).hasSize(1);
        assertThat(PUBLISHER.commands.get(0).eventId()).isEqualTo(eventId);
        assertThat(PUBLISHER.commands.get(0).payload()).isEqualTo(row.get("payload"));
        assertThat(PUBLISHER.transactionActive).containsExactly(false);

        // Terminal: a second tick does not republish.
        assertThat(relay.runOnce()).isZero();
        assertThat(PUBLISHER.commands).hasSize(1);
    }

    @Test
    void failedPublishReleasesToReadyWithClassificationCodeAndBackoff() {
        UUID caseId = createDraftCase("INV-R2");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-r2");
        PUBLISHER.result = new AnalysisPublishResult.Failed(AnalysisPublishError.CONNECT_FAILED);

        assertThat(relay.runOnce()).isZero();

        Map<String, Object> row = outboxRow(caseId, 1);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("last_error_code")).isEqualTo("CONNECT_FAILED");
        assertThat(row.get("claim_token")).isNull();
        assertThat((Integer) row.get("attempt_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select next_attempt_at > clock_timestamp() from analysis_request_outbox where id = ?",
                        Boolean.class,
                        outboxId(caseId, 1)))
                .isTrue();
    }

    @Test
    void activeCallerTransactionRejectsTheRelayBeforeAnyEffect() {
        assertThat(AopUtils.isAopProxy(relay))
                .as("the NEVER transaction boundary must be applied by the proxy")
                .isTrue();
        UUID caseId = createDraftCase("INV-R3");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-r3");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> relay.runOnce()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(PUBLISHER.commands).isEmpty();
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("READY");
    }

    @Test
    void crashAfterPublishBeforeFinalizeRepublishesTheSameEventId() {
        UUID caseId = createDraftCase("INV-R4");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-r4");
        UUID eventId = outboxId(caseId, 1);

        // The message may have reached the broker, but the worker is told to
        // fail before finalization: the request must return to READY.
        PUBLISHER.throwAfterPublish = true;
        assertThat(relay.runOnce()).isZero();
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("READY");
        assertThat(PUBLISHER.commands).hasSize(1);

        // Reclaim and publish again: the event id is stable across attempts.
        PUBLISHER.throwAfterPublish = false;
        releaseBackoff(eventId);
        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(PUBLISHER.commands).hasSize(2);
        assertThat(PUBLISHER.commands.get(0).eventId()).isEqualTo(eventId);
        assertThat(PUBLISHER.commands.get(1).eventId()).isEqualTo(eventId);
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("PUBLISHED");
    }

    static final class RecordingPublisher implements AnalysisRequestPublisher {

        final List<AnalysisPublishCommand> commands = new CopyOnWriteArrayList<>();
        final List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        volatile AnalysisPublishResult result = new AnalysisPublishResult.Published();
        volatile boolean throwAfterPublish;

        void reset() {
            commands.clear();
            transactionActive.clear();
            result = new AnalysisPublishResult.Published();
            throwAfterPublish = false;
        }

        @Override
        public AnalysisPublishResult publish(AnalysisPublishCommand command) {
            commands.add(command);
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (throwAfterPublish) {
                throw new IllegalStateException("simulated crash after publish");
            }
            return result;
        }
    }
}
