package com.invoicematch.core.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.payment.adapter.PaymentExportOutcome;
import com.invoicematch.core.payment.domain.PaymentExportPayload;
import com.invoicematch.core.support.StubErpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1-08 relay tests against real PostgreSQL and an HTTP stub: the full allowed
 * state matrix, canonical payload parity, HTTP outcomes under a real total
 * deadline, crash-window recovery, claim/lease concurrency, attempt-evidence
 * integrity and DB-time lease semantics. Delivery is at-least-once only for the
 * explicit 429 path; the tests never assert exactly-once business delivery.
 */
class PaymentExportRelayIntegrationTest extends AbstractPaymentExportIntegrationTest {

    private static final StubErpServer ERP;
    private static final ControlledExportInterceptor INTERCEPTOR = new ControlledExportInterceptor();
    private static final Instant CLOCK_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(CLOCK_START);

    static {
        try {
            ERP = new StubErpServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TestConfiguration
    static class InterceptorConfiguration {
        @Bean
        PaymentExportInterceptor paymentExportInterceptor() {
            return INTERCEPTOR;
        }

        @Bean
        @org.springframework.context.annotation.Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void erpProperties(DynamicPropertyRegistry registry) {
        registry.add("payment-export.relay.base-url", ERP::baseUrl);
    }

    @AfterAll
    static void stopErp() {
        ERP.close();
    }

    @Autowired
    private PaymentExportRelay relay;

    @Autowired
    private OutboxStore store;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void resetRelay() {
        INTERCEPTOR.reset();
        ERP.reset();
        CLOCK.setTo(CLOCK_START);
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void schedulerIsFailClosedWhenRelayDisabled() {
        assertThat(applicationContext.getBeanNamesForType(PaymentExportScheduler.class)).isEmpty();
    }

    @Test
    void activeCallerTransactionRejectsTheRelayBeforeAnyEffect() {
        assertThat(AopUtils.isAopProxy(relay))
                .as("the NEVER transaction boundary must be applied by the proxy")
                .isTrue();
        UUID caseId = approvedCase("INV-AMBIENT", 5);
        int callsBefore = ERP.requestCount();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> relay.runOnce()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(ERP.requestCount()).isEqualTo(callsBefore);
        assertTuple(caseId, "NOT_SENT", "READY", "EXPORT_PENDING");
    }

    @Test
    void allowedStateMatrixIsRepresentable() {
        UUID caseId = approvedCase("INV-MATRIX", 40);
        // (NOT_SENT, READY, EXPORT_PENDING)
        assertTuple(caseId, "NOT_SENT", "READY", "EXPORT_PENDING");

        ClaimedEvent claimed = claim(caseId);
        // (NOT_SENT, CLAIMED, EXPORT_PENDING)
        assertTuple(caseId, "NOT_SENT", "CLAIMED", "EXPORT_PENDING");
        store.beginSending(claimed.eventId(), claimed.claimToken(), "forger").orElseThrow();
        // (SENDING, SENDING, EXPORT_PENDING)
        assertTuple(caseId, "SENDING", "SENDING", "EXPORT_PENDING");

        // 4xx -> (FAILED, FAILED, EXPORT_PENDING)
        UUID failed = approvedCase("INV-MATRIX-FAILED", 5);
        ERP.respond(400, "{}");
        relay.runOnce();
        assertTuple(failed, "FAILED", "FAILED", "EXPORT_PENDING");
    }

    @Test
    void allowedStateMatrixCoversSuccessRetryAndUnknown() {
        UUID success = approvedCase("INV-M1", 5);
        assertThat(relay.runOnce()).isPositive();
        assertTuple(success, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");

        UUID unknown = approvedCase("INV-M2", 5);
        ERP.respond(500, "{}");
        relay.runOnce();
        assertTuple(unknown, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");

        UUID retry = approvedCase("INV-M3", 5);
        ERP.respond(429, "{}");
        relay.runOnce();
        assertTuple(retry, "RETRY_SCHEDULED", "READY", "EXPORT_PENDING");
        releaseBackoff(outboxId(retry));
        ClaimedEvent claimed = claim(retry);
        assertTuple(retry, "RETRY_SCHEDULED", "CLAIMED", "EXPORT_PENDING");
        store.beginSending(claimed.eventId(), claimed.claimToken(), "forger").orElseThrow();
        assertTuple(retry, "SENDING", "SENDING", "EXPORT_PENDING");
    }

    @Test
    void stoppedRelayLeavesEventThenDeliversForTheClaim() {
        UUID caseId = approvedCase("INV-1", 40);
        assertThat((String) outbox(caseId).get("status")).isEqualTo("READY");
        assertThat((String) payment(caseId).get("status")).isEqualTo("NOT_SENT");
        assertThat(caseStatus(caseId)).isEqualTo("EXPORT_PENDING");

        assertThat(relay.runOnce()).isEqualTo(1);

        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(outbox(caseId).get("delivered_at")).isNotNull();
        assertThat(ERP.requestCount()).isEqualTo(1);

        UUID paymentId = (UUID) payment(caseId).get("id");
        StubErpServer.Captured captured = ERP.last();
        assertThat(captured.idempotencyKey()).isEqualTo(paymentId + ":1");
        assertThat(captured.paymentRequestHeader()).isEqualTo(paymentId.toString());
        assertThat(captured.body()).isEqualTo(javaCanonical(caseId));
        assertThat(captured.body()).doesNotContain("password").doesNotContain("secret");

        assertThat(attemptOutcomes(outboxId(caseId))).containsExactly("SENDING", "ACKNOWLEDGED");
        assertThat(relay.runOnce()).isZero();
        assertThat(ERP.requestCount()).isEqualTo(1);
    }

    @Test
    void canonicalPayloadMatchesTheDatabaseFunction() {
        UUID caseId = approvedCase("INV-CANON", 40);
        UUID paymentId = (UUID) payment(caseId).get("id");

        String dbCanonical = jdbc.queryForObject(
                "select payment_export_canonical_payload(?, 1)", String.class, paymentId);
        String dbHash = jdbc.queryForObject(
                "select encode(sha256(convert_to(payment_export_canonical_payload(?, 1), 'UTF8')), 'hex')",
                String.class,
                paymentId);

        assertThat(dbCanonical).isEqualTo(javaCanonical(caseId));
        assertThat(dbHash).isEqualTo(PaymentExportPayload.sha256Hex(dbCanonical));
        assertThat(outbox(caseId).get("payload_hash")).isEqualTo(dbHash);
    }

    @Test
    void newApprovalWithSpecialCharacterPurchaseOrderMatchesTheCanonicalContract() {
        String oddPo = "PO-\"\\\r\n한글😀";
        respondPurchasing(oddPo);
        UUID caseId = approvedCase("INV-ODD", 40, oddPo);

        UUID paymentId = (UUID) payment(caseId).get("id");
        UUID snapshotId = (UUID) payment(caseId).get("review_snapshot_id");
        String reviewHash = (String) payment(caseId).get("review_payload_hash");
        long amount = (Long) payment(caseId).get("amount");
        String externalKey = (String) payment(caseId).get("external_request_key");

        PaymentExportPayload expected = PaymentExportPayload.forPaymentRequestFields(
                paymentId, caseId, oddPo, snapshotId, reviewHash, externalKey, amount, "KRW", 1L);
        String dbCanonical = jdbc.queryForObject(
                "select payment_export_canonical_payload(?, 1)", String.class, paymentId);

        assertThat(dbCanonical).isEqualTo(expected.canonicalJson());
        assertThat(outbox(caseId).get("payload_hash"))
                .isEqualTo(PaymentExportPayload.sha256Hex(expected.canonicalJson()));
    }

    @Test
    void nonRetryable4xxFailsPaymentAndLeavesCaseExportPending() {
        UUID caseId = approvedCase("INV-4XX", 40);
        ERP.respond(422, "{\"error\":\"invalid\"}");
        assertThat(relay.runOnce()).isZero();
        assertTuple(caseId, "FAILED", "FAILED", "EXPORT_PENDING");
    }

    @Test
    void serverErrorIsConservativeResultUnknownAndNeverResent() {
        UUID caseId = approvedCase("INV-5XX", 40);
        ERP.respond(503, "{\"error\":\"busy\"}");
        assertThat(relay.runOnce()).isZero();
        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertThat(relay.runOnce()).isZero();
        assertThat(ERP.requestCount()).isEqualTo(1);
        assertThat(attemptOutcomes(outboxId(caseId))).contains("RESULT_UNKNOWN");
        assertThat((String) jdbc.queryForObject(
                        "select http_status::text from outbox_delivery_attempt"
                                + " where outbox_event_id = ? and outcome = 'RESULT_UNKNOWN'",
                        String.class,
                        outboxId(caseId)))
                .isEqualTo("503");
    }

    @Test
    void totalRequestDeadlineTimeoutIsResultUnknownNotFailure() {
        UUID caseId = approvedCase("INV-TIME", 40);
        // Server waits longer than the configured total request deadline.
        ERP.respondAfter(200, "{\"accepted\":true}", Duration.ofSeconds(3));
        assertThat(relay.runOnce()).isZero();
        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertThat(attemptOutcomes(outboxId(caseId))).contains("RESULT_UNKNOWN");
        assertThat((String) jdbc.queryForObject(
                        "select error_code from outbox_delivery_attempt"
                                + " where outbox_event_id = ? and outcome = 'RESULT_UNKNOWN'",
                        String.class,
                        outboxId(caseId)))
                .isEqualTo("TIMEOUT");
    }

    @Test
    void trickledResponseBodyIsAbortedByTheTotalDeadline() {
        UUID caseId = approvedCase("INV-TRICKLE", 40);
        // Headers arrive immediately, then the body trickles well past the 2s
        // total deadline; a per-read timeout would never fire.
        ERP.respondTrickle(20, Duration.ofMillis(300));
        assertThat(relay.runOnce()).isZero();
        assertThat((String) outbox(caseId).get("status")).isEqualTo("RESULT_UNKNOWN");
        assertThat(ERP.requestCount()).isEqualTo(1);
    }

    @Test
    void redirectStatusIsResultUnknownWithStoredStatusAndNoRollback() {
        for (int status : List.of(301, 302, 307)) {
            UUID caseId = approvedCase("INV-REDIRECT-" + status, 5);
            ERP.respond(status, "{}");
            assertThat(relay.runOnce()).isZero();
            assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
            Map<String, Object> attempt = jdbc.queryForMap(
                    "select http_status, error_code from outbox_delivery_attempt"
                            + " where outbox_event_id = ? and outcome = 'RESULT_UNKNOWN'",
                    outboxId(caseId));
            assertThat(attempt.get("http_status")).isEqualTo(status);
            assertThat(attempt.get("error_code")).isEqualTo("HTTP_" + status);
        }
    }

    @Test
    void oneRedirectDoesNotAbortTheRestOfTheBatch() {
        UUID first = approvedCase("INV-BATCH-1", 5);
        UUID second = approvedCase("INV-BATCH-2", 5);
        ERP.respond(307, "{}");

        assertThat(relay.runOnce()).isZero();

        assertTuple(first, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertTuple(second, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertThat(ERP.requestCount()).isEqualTo(2);
    }

    @Test
    void malformedAndOversizedSuccessBodyStillAcknowledges() {
        List<String> bodies = List.of("not-json", "{\"unexpected\":true}", "x".repeat(200_000));
        for (int i = 0; i < bodies.size(); i++) {
            UUID caseId = approvedCase("INV-BODY-" + i, 5);
            ERP.respond(200, bodies.get(i));
            assertThat(relay.runOnce()).isEqualTo(1);
            assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        }
    }

    @Test
    void explicit429SchedulesBoundedRetryWithSameKeyThenSucceeds() {
        UUID caseId = approvedCase("INV-429", 40);
        UUID paymentId = (UUID) payment(caseId).get("id");
        ERP.respond(429, "{\"error\":\"slow down\"}");
        assertThat(relay.runOnce()).isZero();
        assertTuple(caseId, "RETRY_SCHEDULED", "READY", "EXPORT_PENDING");
        String firstKey = ERP.last().idempotencyKey();
        String firstBody = ERP.last().body();

        releaseBackoff(outboxId(caseId));
        ERP.respond(200, "{\"accepted\":true}");
        assertThat(relay.runOnce()).isEqualTo(1);
        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(ERP.requestCount()).isEqualTo(2);
        assertThat(ERP.last().idempotencyKey()).isEqualTo(firstKey).isEqualTo(paymentId + ":1");
        assertThat(ERP.last().body()).isEqualTo(firstBody);
    }

    @Test
    void exhausted429BecomesTerminalFailure() {
        UUID caseId = approvedCase("INV-429MAX", 40);
        ERP.respond(429, "{\"error\":\"slow down\"}");
        for (int attempt = 1; attempt <= 3; attempt++) {
            relay.runOnce();
            releaseBackoff(outboxId(caseId));
        }
        assertTuple(caseId, "FAILED", "FAILED", "EXPORT_PENDING");
        assertThat(ERP.requestCount()).isEqualTo(3);
        assertThat(attemptOutcomes(outboxId(caseId))).contains("RETRY_SCHEDULED", "FAILED");
    }

    @Test
    void crashAfterClaimBeforeSendingCanSafelyRecoverAndSend() {
        UUID caseId = approvedCase("INV-W1", 40);
        INTERCEPTOR.failAt(ControlledExportInterceptor.Stage.AFTER_CLAIM);
        assertThatThrownBy(relay::runOnce).isInstanceOf(SimulatedCrash.class);
        assertThat((String) outbox(caseId).get("status")).isEqualTo("CLAIMED");
        assertThat(ERP.requestCount()).isZero();

        INTERCEPTOR.reset();
        expireRow(outboxId(caseId));
        assertThat(relay.runOnce()).isEqualTo(1);
        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
    }

    @Test
    void crashAfterSendingBeforeHttpBecomesResultUnknownOnExpiry() {
        UUID caseId = approvedCase("INV-W2", 40);
        INTERCEPTOR.failAt(ControlledExportInterceptor.Stage.AFTER_SENDING);
        assertThatThrownBy(relay::runOnce).isInstanceOf(SimulatedCrash.class);
        assertThat((String) outbox(caseId).get("status")).isEqualTo("SENDING");
        assertThat(ERP.requestCount()).isZero();

        INTERCEPTOR.reset();
        expireRow(outboxId(caseId));
        relay.runOnce();
        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertThat(ERP.requestCount()).isZero();
        assertThat(attemptOutcomes(outboxId(caseId))).contains("LEASE_EXPIRED");
    }

    @Test
    void httpSuccessThenCrashBeforeDbMarkBecomesResultUnknownWithoutResend() {
        UUID caseId = approvedCase("INV-W3", 40);
        ERP.respond(200, "{\"accepted\":true}");
        INTERCEPTOR.failAt(ControlledExportInterceptor.Stage.BEFORE_FINALIZE);
        assertThatThrownBy(relay::runOnce).isInstanceOf(SimulatedCrash.class);
        assertThat((String) outbox(caseId).get("status")).isEqualTo("SENDING");
        assertThat(ERP.requestCount()).isEqualTo(1);

        INTERCEPTOR.reset();
        expireRow(outboxId(caseId));
        relay.runOnce();
        assertThat((String) outbox(caseId).get("status")).isEqualTo("RESULT_UNKNOWN");
        assertThat(ERP.requestCount()).isEqualTo(1);
    }

    @Test
    void slowResponseIsNotRecoveredWhileTheLeaseIsValid() throws Exception {
        UUID caseId = approvedCase("INV-SLOW", 40);
        ERP.respondAfter(200, "{\"accepted\":true}", Duration.ofMillis(1200));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<Integer> running = pool.submit(() -> {
                try {
                    return relay.runOnce();
                } catch (RuntimeException e) {
                    failure.set(e);
                    throw e;
                }
            });
            Thread.sleep(300);
            assertThat(store.recoverExpired()).isZero();
            assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(failure.get()).isNull();
            assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clockSkewBetweenNodesDoesNotAffectLeaseRecovery() {
        // A wildly wrong JVM clock must not matter: leases use DB time.
        CLOCK.setTo(Instant.parse("2035-06-01T00:00:00Z"));
        UUID caseId = approvedCase("INV-SKEW", 40);
        ERP.respond(200, "{\"accepted\":true}");
        assertThat(relay.runOnce()).isEqualTo(1);
        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");

        CLOCK.setTo(Instant.parse("2001-01-01T00:00:00Z"));
        UUID second = approvedCase("INV-SKEW-2", 5);
        ERP.respond(200, "{\"accepted\":true}");
        // The second relay tick still finds and delivers the event.
        relay.runOnce();
        assertTuple(second, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
    }

    @Test
    void recoveryAndFinalizationTakeLocksInTheSameCanonicalOrder() throws Exception {
        UUID caseId = approvedCase("INV-LOCK", 40);
        UUID eventId = outboxId(caseId);
        UUID paymentId = (UUID) payment(caseId).get("id");
        ClaimedEvent claimed = store.claimBatch(10, "worker-lock", Duration.ofSeconds(30)).get(0);
        store.beginSending(eventId, claimed.claimToken(), "worker-lock").orElseThrow();
        expireRow(eventId);

        CountDownLatch paymentLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("set local deadlock_timeout = '100ms'");
                        statement.execute(
                                "select status from payment_request where id = '" + paymentId + "' for update");
                    }
                    paymentLocked.countDown();
                    release.await(15, TimeUnit.SECONDS);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("select id from outbox_event where id = '" + eventId + "' for update");
                    }
                    connection.rollback();
                } catch (Exception e) {
                    holderFailure.set(e);
                }
            });
            assertThat(paymentLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> recovery = pool.submit(store::recoverExpired);
            Thread.sleep(400);
            assertThat(recovery.isDone())
                    .as("recovery must block on the payment lock before touching the outbox")
                    .isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(holderFailure.get()).isNull();
            recovery.get(30, TimeUnit.SECONDS);
            assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleTokenCannotOverwriteACurrentClaim() {
        UUID caseId = approvedCase("INV-STALE", 40);
        UUID outboxId = outboxId(caseId);
        UUID paymentId = (UUID) payment(caseId).get("id");

        List<ClaimedEvent> firstClaim = store.claimBatch(10, "worker-1", Duration.ofSeconds(1));
        UUID staleToken = firstClaim.get(0).claimToken();
        expireRow(outboxId);
        store.recoverExpired();
        assertThat((String) outbox(caseId).get("status")).isEqualTo("READY");

        List<ClaimedEvent> secondClaim = store.claimBatch(10, "worker-2", Duration.ofMinutes(1));
        UUID currentToken = secondClaim.get(0).claimToken();
        SendingEvent current = store.beginSending(outboxId, currentToken, "worker-2").orElseThrow();

        SendingEvent stale = new SendingEvent(
                outboxId, staleToken, paymentId, caseId, current.idempotencyKey(), current.payload(), 1);
        assertThatThrownBy(() -> store.finalizeSuccess(stale, 200, CLOCK.instant(), "worker-1"))
                .isInstanceOf(StaleClaimException.class);
        assertTuple(caseId, "SENDING", "SENDING", "EXPORT_PENDING");
    }

    @Test
    void twoWorkersClaimEachEventAtMostOnce() throws Exception {
        for (int i = 1; i <= 5; i++) {
            approvedCase("INV-CLAIM-" + i, 5);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<UUID> claimed = new CopyOnWriteArrayList<>();
        try {
            Future<?> a = pool.submit(() -> claimAfter(start, "worker-a", claimed));
            Future<?> b = pool.submit(() -> claimAfter(start, "worker-b", claimed));
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(claimed).hasSize(5);
        assertThat(claimed).doesNotHaveDuplicates();
    }

    @Test
    void rawSqlCannotForgeOrRewindOutboxState() {
        UUID caseId = approvedCase("INV-RAW", 40);
        UUID outboxId = outboxId(caseId);
        UUID paymentId = (UUID) payment(caseId).get("id");

        assertThatThrownBy(() -> jdbc.update("update outbox_event set payload = '{}'::jsonb where id = ?", outboxId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from outbox_event where id = ?", outboxId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("not deletable");
        assertThatThrownBy(() -> jdbc.update("update outbox_event set status = 'DELIVERED' where id = ?", outboxId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("illegal outbox status transition");
        assertThatThrownBy(() -> jdbc.update("update outbox_event set status = 'CLAIMED' where id = ?", outboxId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("requires worker, claim token and lease");

        String validPayload = jdbc.queryForObject(
                "select payment_export_canonical_payload(?, 1)", String.class, paymentId);
        String validHash = PaymentExportPayload.sha256Hex(validPayload);
        assertThatThrownBy(() -> insertForgedOutbox(
                        caseId, paymentId, paymentId + ":1", "{\"amount\":1}", validHash, "READY"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("canonical");
        assertThatThrownBy(() -> insertForgedOutbox(
                        caseId, paymentId, "FORGED-KEY", validPayload, validHash, "READY"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("idempotency key");
    }

    @Test
    void rawSqlCannotStealAClaimIdentity() {
        UUID caseId = approvedCase("INV-THEFT", 40);
        UUID outboxId = outboxId(caseId);
        store.claimBatch(10, "worker-owner", Duration.ofMinutes(1));

        assertThatThrownBy(() -> jdbc.update(
                        "update outbox_event set claim_token = ? where id = ? and status = 'CLAIMED'",
                        UUID.randomUUID(), outboxId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("claim identity cannot change");
        assertThatThrownBy(() -> jdbc.update(
                        "update outbox_event set worker_id = 'thief' where id = ? and status = 'CLAIMED'", outboxId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("claim identity cannot change");
    }

    @Test
    void rawSqlCrossStateGuardsRejectEveryInconsistentTuple() {
        UUID caseId = approvedCase("INV-CROSS", 40);
        UUID paymentId = (UUID) payment(caseId).get("id");

        // (a) ACKNOWLEDGED payment while the outbox is still READY.
        assertCommitRejected(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("update payment_request set status = 'SENDING' where id = ?", paymentId);
            jdbc.update("update payment_request set status = 'ACKNOWLEDGED' where id = ?", paymentId);
        }), "DELIVERED outbox");

        // (b) FAILED payment while the outbox is still READY.
        assertCommitRejected(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("update payment_request set status = 'SENDING' where id = ?", paymentId);
            jdbc.update("update payment_request set status = 'FAILED' where id = ?", paymentId);
        }), "inconsistent payment/outbox pair");

        // (d) case EXPORTED while the payment is still NOT_SENT.
        assertCommitRejected(() -> transaction.executeWithoutResult(status ->
                jdbc.update("update invoice_case set status = 'EXPORTED' where id = ?", caseId)),
                "EXPORT_PENDING");

        assertTuple(caseId, "NOT_SENT", "READY", "EXPORT_PENDING");
    }

    @Test
    void rawSqlRejectsResultUnknownPaymentWithSendingOutbox() {
        // Kept separate so the claim only ever sees this case's event.
        UUID caseId = approvedCase("INV-CROSS-SENDING", 5);
        UUID paymentId = (UUID) payment(caseId).get("id");
        UUID outboxId = outboxId(caseId);
        ClaimedEvent claimed = claim(caseId);
        store.beginSending(outboxId, claimed.claimToken(), "forger").orElseThrow();

        assertCommitRejected(() -> transaction.executeWithoutResult(status ->
                jdbc.update("update payment_request set status = 'RESULT_UNKNOWN' where id = ?", paymentId)),
                "inconsistent payment/outbox pair");

        assertTuple(caseId, "SENDING", "SENDING", "EXPORT_PENDING");
    }

    @Test
    void rawSqlAttemptLedgerRejectsForgedEvidence() {
        UUID caseId = approvedCase("INV-LEDGER", 40);
        UUID paymentId = (UUID) payment(caseId).get("id");
        UUID outboxId = outboxId(caseId);

        // Standalone SENDING attempt on a READY event.
        assertThatThrownBy(() -> insertAttempt(outboxId, paymentId, UUID.randomUUID(), 1, "SENDING", null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CLAIMED event");

        ClaimedEvent claimed = claim(caseId);
        // SENDING attempt with a jumped attempt number.
        assertThatThrownBy(() -> insertAttempt(
                        outboxId, paymentId, claimed.claimToken(), 5, "SENDING", null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("attempt_count + 1");
        // SENDING attempt with a mixed token.
        assertThatThrownBy(() -> insertAttempt(
                        outboxId, paymentId, UUID.randomUUID(), 1, "SENDING", null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("claim identity");

        store.beginSending(outboxId, claimed.claimToken(), "forger").orElseThrow();
        // Wrong HTTP status for the outcome.
        assertThatThrownBy(() -> insertAttempt(
                        outboxId, paymentId, claimed.claimToken(), 1, "ACKNOWLEDGED", 500, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("2xx");
        assertThatThrownBy(() -> insertAttempt(
                        outboxId, paymentId, claimed.claimToken(), 1, "RETRY_SCHEDULED", 200, "RATE_LIMITED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("429");

        // Terminal evidence for an attempt that has no SENDING evidence: force
        // the event to SENDING without writing the SENDING row.
        UUID caseB = approvedCase("INV-LEDGER-B", 5);
        UUID paymentB = (UUID) payment(caseB).get("id");
        UUID outboxB = outboxId(caseB);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            UUID token = UUID.randomUUID();
            jdbc.update(
                    "update outbox_event set status = 'CLAIMED', worker_id = 'forger', claim_token = ?,"
                            + " lease_expires_at = clock_timestamp() + interval '1 minute'"
                            + " where id = ? and status = 'READY'",
                    token, outboxB);
            jdbc.update(
                    "update outbox_event set status = 'SENDING', attempt_count = 1"
                            + " where id = ? and status = 'CLAIMED' and claim_token = ?",
                    outboxB, token);
            insertAttempt(outboxB, paymentB, token, 1, "ACKNOWLEDGED", 200, null);
        })).satisfies(error -> assertThat(rootMessage(error)).contains("SENDING evidence"));
    }

    @Test
    void standaloneSendingEvidenceCannotCommit() {
        UUID caseId = approvedCase("INV-STANDALONE-SEND", 5);
        UUID paymentId = (UUID) payment(caseId).get("id");
        UUID outboxId = outboxId(caseId);
        ClaimedEvent claimed = claim(caseId);

        // The immediate guard passes (CLAIMED, matching token/worker,
        // attempt_count + 1) but no state transition follows, so the deferred
        // commit guard rejects the standalone row.
        assertThatThrownBy(() -> insertAttempt(
                        outboxId, paymentId, claimed.claimToken(), 1, "SENDING", null, null))
                .satisfies(error -> assertThat(rootMessage(error))
                        .contains("SENDING evidence requires a committed SENDING outbox"));
        assertTuple(caseId, "NOT_SENT", "CLAIMED", "EXPORT_PENDING");
    }

    @Test
    void standaloneTerminalEvidenceCannotCommit() {
        UUID caseId = approvedCase("INV-STANDALONE-TERM", 5);
        UUID paymentId = (UUID) payment(caseId).get("id");
        UUID outboxId = outboxId(caseId);
        ClaimedEvent claimed = claim(caseId);
        store.beginSending(outboxId, claimed.claimToken(), "forger").orElseThrow();

        // Valid shape and SENDING evidence exist, but the committed outbox is
        // still SENDING, not DELIVERED.
        assertThatThrownBy(() -> insertAttempt(
                        outboxId, paymentId, claimed.claimToken(), 1, "ACKNOWLEDGED", 200, null))
                .satisfies(error -> assertThat(rootMessage(error))
                        .contains("ACKNOWLEDGED evidence requires a committed DELIVERED outbox"));
        assertTuple(caseId, "SENDING", "SENDING", "EXPORT_PENDING");
    }

    @Test
    void conflictingTerminalEvidenceForOneAttemptIsRejected() {
        UUID caseId = approvedCase("INV-CONFLICT", 5);
        UUID paymentId = (UUID) payment(caseId).get("id");
        UUID outboxId = outboxId(caseId);
        ClaimedEvent claimed = claim(caseId);
        store.beginSending(outboxId, claimed.claimToken(), "forger").orElseThrow();

        // One attempt cannot carry two terminal outcomes.
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertAttempt(outboxId, paymentId, claimed.claimToken(), 1, "ACKNOWLEDGED", 200, null);
            insertAttempt(outboxId, paymentId, claimed.claimToken(), 1, "FAILED", 400, "HTTP_400");
        })).satisfies(error -> assertThat(rootMessage(error)).contains("ux_outbox_attempt_terminal"));
        assertTuple(caseId, "SENDING", "SENDING", "EXPORT_PENDING");
    }

    @Test
    void attemptLedgerUniqueRejectsDuplicateEvidence() {
        UUID caseId = approvedCase("INV-LEDGER-DUP", 5);
        UUID paymentId = (UUID) payment(caseId).get("id");
        UUID outboxId = outboxId(caseId);
        ClaimedEvent claimed = claim(caseId);

        // Two SENDING rows for one attempt in one transaction: the second
        // violates the unique constraint before any commit-time validation.
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertAttempt(outboxId, paymentId, claimed.claimToken(), 1, "SENDING", null, null);
            insertAttempt(outboxId, paymentId, claimed.claimToken(), 1, "SENDING", null, null);
        })).satisfies(error -> assertThat(rootMessage(error))
                .contains("ux_outbox_attempt_event_number_outcome"));
        assertTuple(caseId, "NOT_SENT", "CLAIMED", "EXPORT_PENDING");
    }

    private void claimAfter(CountDownLatch start, String workerId, List<UUID> claimed) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (ClaimedEvent event : store.claimBatch(10, workerId, Duration.ofMinutes(1))) {
            claimed.add(event.eventId());
        }
    }

    private ClaimedEvent claim(UUID caseId) {
        List<ClaimedEvent> claimed = store.claimBatch(10, "forger", Duration.ofMinutes(5));
        assertThat(claimed).hasSize(1);
        return claimed.get(0);
    }

    private void assertTuple(UUID caseId, String payment, String outbox, String caseStatus) {
        assertThat((String) payment(caseId).get("status")).as("payment status").isEqualTo(payment);
        assertThat((String) outbox(caseId).get("status")).as("outbox status").isEqualTo(outbox);
        assertThat(caseStatus(caseId)).as("case status").isEqualTo(caseStatus);
    }

    private List<String> attemptOutcomes(UUID outboxId) {
        return jdbc.queryForList(
                "select outcome from outbox_delivery_attempt"
                        + " where outbox_event_id = ? order by occurred_at, attempt_number",
                String.class,
                outboxId);
    }

    /** Test-only lease expiry: the worker-owned lease deadline is adjustable. */
    private void expireRow(UUID outboxId) {
        jdbc.update(
                "update outbox_event set lease_expires_at = clock_timestamp() - interval '1 minute'"
                        + " where id = ?",
                outboxId);
    }

    private void releaseBackoff(UUID outboxId) {
        jdbc.update(
                "update outbox_event set next_attempt_at = clock_timestamp() - interval '1 minute' where id = ?",
                outboxId);
    }

    private String javaCanonical(UUID caseId) {
        UUID paymentId = (UUID) payment(caseId).get("id");
        return jdbc.queryForObject(
                "select payment_export_canonical_payload(?, 1)", String.class, paymentId);
    }

    private void insertAttempt(
            UUID outboxId, UUID paymentId, UUID token, int attemptNumber, String outcome,
            Integer httpStatus, String errorCode) {
        jdbc.update(
                "insert into outbox_delivery_attempt (id, outbox_event_id, payment_request_id, claim_token,"
                        + " worker_id, attempt_number, outcome, http_status, error_code, occurred_at)"
                        + " values (?, ?, ?, ?, 'forger', ?, ?, ?, ?, clock_timestamp())",
                UUID.randomUUID(), outboxId, paymentId, token, attemptNumber, outcome, httpStatus, errorCode);
    }

    private void insertForgedOutbox(
            UUID caseId, UUID paymentId, String key, String payload, String hash, String status) {
        jdbc.update(
                "insert into outbox_event (id, aggregate_type, aggregate_id, payment_request_id, invoice_case_id,"
                        + " event_type, export_version, idempotency_key, payload, payload_hash, status,"
                        + " attempt_count, created_at, updated_at)"
                        + " values (?, 'PAYMENT_REQUEST', ?, ?, ?, 'PaymentRequestExportRequested', 1, ?,"
                        + " cast(? as jsonb), ?, ?, 0, clock_timestamp(), clock_timestamp())",
                UUID.randomUUID(), paymentId, paymentId, caseId, key, payload, hash, status);
    }

    private void assertCommitRejected(Runnable statement, String fragment) {
        assertThatThrownBy(statement::run)
                .satisfies(error -> assertThat(rootMessage(error)).contains(fragment));
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    /** Test clock: leases use DB time, but this lets a test prove clock independence. */
    static final class MutableClock extends Clock {
        private volatile Instant current;

        MutableClock(Instant start) {
            this.current = start;
        }

        void setTo(Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String message) {
            super(message);
        }
    }

    /** Injects the contract's crash windows after the relevant commit. */
    static final class ControlledExportInterceptor implements PaymentExportInterceptor {

        enum Stage {
            AFTER_CLAIM,
            AFTER_SENDING,
            BEFORE_FINALIZE
        }

        private volatile Stage failAt;

        void reset() {
            failAt = null;
        }

        void failAt(Stage stage) {
            failAt = stage;
        }

        private void crashIf(Stage stage) {
            if (failAt == stage) {
                throw new SimulatedCrash("injected crash at " + stage);
            }
        }

        @Override
        public void afterClaimed(UUID eventId) {
            crashIf(Stage.AFTER_CLAIM);
        }

        @Override
        public void afterSendingCommitted(UUID eventId) {
            crashIf(Stage.AFTER_SENDING);
        }

        @Override
        public void beforeFinalize(UUID eventId, PaymentExportOutcome outcome) {
            crashIf(Stage.BEFORE_FINALIZE);
        }
    }
}
