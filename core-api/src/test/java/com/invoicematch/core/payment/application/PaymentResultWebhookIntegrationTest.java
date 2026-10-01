package com.invoicematch.core.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.invoicematch.core.payment.persistence.ClaimedEvent;
import com.invoicematch.core.payment.persistence.OutboxStore;
import com.invoicematch.core.payment.webhook.MockErpSignatureVerifier;
import com.invoicematch.core.payment.webhook.PaymentResultWebhookController;
import com.invoicematch.core.support.StubErpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1-09 webhook tests against real PostgreSQL: signature verification, the
 * (provider, externalEventId) dedup contract, payload conflicts, unknown and
 * mismatched payment keys, illegal/out-of-order results, lost-response
 * convergence, the race with an in-flight send, concurrency and the DB guard.
 */
@AutoConfigureMockMvc
class PaymentResultWebhookIntegrationTest extends AbstractPaymentExportIntegrationTest {

    private static final String SECRET = "test-webhook-secret";
    private static final StubErpServer ERP;

    static {
        try {
            ERP = new StubErpServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void erpProperties(DynamicPropertyRegistry registry) {
        registry.add("payment-export.relay.base-url", ERP::baseUrl);
        registry.add("mock-erp.webhook.secret", () -> SECRET);
    }

    @AfterAll
    static void stopErp() {
        ERP.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentExportRelay relay;

    @Autowired
    private OutboxStore outboxStore;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void acknowledgedWebhookConvergesAfterALostResponse() throws Exception {
        UUID caseId = approvedCase("INV-WH-ACK", 5);
        ERP.respond(503, "{}");
        relay.runOnce();
        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");

        UUID paymentId = paymentId(caseId);
        String body = body("evt-ack-1", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST");
        postSigned(body, nowSeconds()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));

        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(count("payment_result_event")).isEqualTo(1);
    }

    @Test
    void aDuplicateWebhookIsAReplayWithOneEffect() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-DUP");
        UUID paymentId = paymentId(caseId);
        String body = body("evt-dup", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST");

        postSigned(body, nowSeconds()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        postSigned(body, nowSeconds()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPLAY"));

        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("payment_result_event")).isEqualTo(1);
    }

    @Test
    void onlyThePayloadHashIsStoredNotTheBodyOrSecret() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-HASH");
        UUID paymentId = paymentId(caseId);
        String body = body("evt-hash", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST");
        postSigned(body, nowSeconds()).andExpect(status().isOk());

        String stored = jdbc.queryForObject(
                "select payload_hash from payment_result_event where external_event_id = 'evt-hash'", String.class);
        assertThat(stored).isEqualTo(sha256Hex(body)).doesNotContain(SECRET);
    }

    @Test
    void sameEventIdWithADifferentPayloadIsAConflict() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-CONFLICT");
        UUID paymentId = paymentId(caseId);
        String body = body("evt-conflict", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST");
        postSigned(body, nowSeconds()).andExpect(status().isOk());

        String different = body("evt-conflict", key(caseId), paymentId, "FAILED", "ERP-OTHER");
        postSigned(different, nowSeconds()).andExpect(status().isConflict());

        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(count("payment_result_event")).isEqualTo(1);
    }

    @Test
    void forgedMissingAndExpiredSignaturesAreRejectedWithNoEffect() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-SIG");
        UUID paymentId = paymentId(caseId);
        String body = body("evt-sig", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST");

        mockMvc.perform(post(PaymentResultWebhookController.PATH)
                        .header(MockErpSignatureVerifier.TIMESTAMP_HEADER, Long.toString(nowSeconds()))
                        .header(MockErpSignatureVerifier.SIGNATURE_HEADER, "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(PaymentResultWebhookController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        long expired = nowSeconds() - 3600;
        mockMvc.perform(post(PaymentResultWebhookController.PATH)
                        .header(MockErpSignatureVerifier.TIMESTAMP_HEADER, Long.toString(expired))
                        .header(MockErpSignatureVerifier.SIGNATURE_HEADER, sign(SECRET, expired, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertThat(count("payment_result_event")).isZero();
    }

    @Test
    void unknownPaymentKeyIsRejectedWithNoEffect() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-UNKNOWN");
        UUID stranger = UUID.randomUUID();
        String body = body("evt-unknown", stranger + ":1", stranger, "ACKNOWLEDGED", "ERP-TEST");

        postSigned(body, nowSeconds()).andExpect(status().isNotFound());

        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertThat(count("payment_request")).isEqualTo(1);
        assertThat(count("payment_result_event")).isZero();
    }

    @Test
    void paymentRequestIdThatDisagreesWithThePaymentKeyIsAConflict() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-MISMATCH");
        String body = body("evt-mismatch", key(caseId), UUID.randomUUID(), "ACKNOWLEDGED", "ERP-TEST");

        postSigned(body, nowSeconds()).andExpect(status().isConflict());

        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        assertThat(count("payment_result_event")).isZero();
    }

    @Test
    void failedThenAcknowledgedOutOfOrderIsAConflict() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-ORDER");
        UUID paymentId = paymentId(caseId);
        postSigned(body("evt-fail-1", key(caseId), paymentId, "FAILED", "ERP-TEST"), nowSeconds())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertTuple(caseId, "FAILED", "FAILED", "EXPORT_PENDING");

        postSigned(body("evt-ack-2", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST"), nowSeconds())
                .andExpect(status().isConflict());

        assertTuple(caseId, "FAILED", "FAILED", "EXPORT_PENDING");
        assertThat(count("payment_result_event")).isEqualTo(1);
    }

    @Test
    void acknowledgedThenFailedIsAConflictAndKeepsTheExport() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-DOWNGRADE");
        UUID paymentId = paymentId(caseId);
        postSigned(body("evt-up", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST"), nowSeconds())
                .andExpect(status().isOk());

        postSigned(body("evt-down", key(caseId), paymentId, "FAILED", "ERP-TEST"), nowSeconds())
                .andExpect(status().isConflict());

        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(count("payment_result_event")).isEqualTo(1);
    }

    @Test
    void acknowledgedWebhookResolvesAnInFlightSendAndTheRelayBecomesANoop() throws Exception {
        UUID caseId = approvedCase("INV-WH-SENDING", 5);
        UUID eventId = outboxId(caseId);
        List<ClaimedEvent> claimed = outboxStore.claimBatch(10, "webhook-test", java.time.Duration.ofMinutes(5));
        ClaimedEvent claim = claimed.stream()
                .filter(candidate -> candidate.eventId().equals(eventId))
                .findFirst()
                .orElseThrow();
        outboxStore.beginSending(eventId, claim.claimToken(), "webhook-test").orElseThrow();
        assertTuple(caseId, "SENDING", "SENDING", "EXPORT_PENDING");

        int requestsBefore = ERP.requestCount();
        postSigned(body("evt-inflight", key(caseId), paymentId(caseId), "ACKNOWLEDGED", "ERP-TEST"), nowSeconds())
                .andExpect(status().isOk());

        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(relay.runOnce()).isZero();
        assertThat(ERP.requestCount()).isEqualTo(requestsBefore);
        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
    }

    @Test
    void parallelDuplicateWebhooksApplyExactlyOnce() throws Exception {
        UUID caseId = resultUnknownCase("INV-WH-PARALLEL");
        UUID paymentId = paymentId(caseId);
        String body = body("evt-parallel", key(caseId), paymentId, "ACKNOWLEDGED", "ERP-TEST");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> deliver(body, start, statuses)),
                    pool.submit(() -> deliver(body, start, statuses)));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 200);
        assertThat(count("payment_result_event")).isEqualTo(1);
        assertThat(count("payment_request")).isEqualTo(1);
        assertTuple(caseId, "ACKNOWLEDGED", "DELIVERED", "EXPORTED");
        assertThat(jdbc.queryForList(
                        "select external_event_id from payment_result_event", String.class))
                .containsExactly("evt-parallel");
    }

    @Test
    void aStandaloneForgedResultEventCannotCommit() {
        UUID caseId = approvedCase("INV-WH-FORGE", 5);
        UUID paymentId = paymentId(caseId);
        UUID outboxId = outboxId(caseId);

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update(
                                "insert into payment_result_event (id, provider, external_event_id,"
                                        + " external_payment_key, payment_request_id, outbox_event_id, outcome,"
                                        + " payload_hash, external_reference, received_at)"
                                        + " values (?, 'mock-erp', 'evt-forged', ?, ?, ?, 'ACKNOWLEDGED',"
                                        + " ?, null, clock_timestamp())",
                                UUID.randomUUID(),
                                paymentId + ":1",
                                paymentId,
                                outboxId,
                                sha256Hex("forged"))))
                .satisfies(error -> assertThat(rootMessage(error))
                        .contains("ACKNOWLEDGED result requires")));
        assertTuple(caseId, "NOT_SENT", "READY", "EXPORT_PENDING");
    }

    private void deliver(String body, CountDownLatch start, List<Integer> statuses) {
        try {
            start.await(10, TimeUnit.SECONDS);
            statuses.add(postSigned(body, nowSeconds()).andReturn().getResponse().getStatus());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID resultUnknownCase(String invoiceNumber) {
        UUID caseId = approvedCase(invoiceNumber, 5);
        ERP.respond(503, "{}");
        relay.runOnce();
        assertTuple(caseId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "EXPORT_PENDING");
        ERP.respond(200, "{\"accepted\":true}");
        return caseId;
    }

    private ResultActions postSigned(String body, long timestamp) throws Exception {
        return mockMvc.perform(post(PaymentResultWebhookController.PATH)
                .header(MockErpSignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(MockErpSignatureVerifier.SIGNATURE_HEADER, sign(SECRET, timestamp, body))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(String eventId, String externalPaymentKey, UUID paymentRequestId, String outcome, String ref) {
        return "{"
                + "\"provider\":\"mock-erp\","
                + "\"externalEventId\":\"" + eventId + "\","
                + "\"externalPaymentKey\":\"" + externalPaymentKey + "\","
                + "\"paymentRequestId\":\"" + paymentRequestId + "\","
                + "\"outcome\":\"" + outcome + "\","
                + "\"externalReference\":\"" + ref + "\""
                + "}";
    }

    private UUID paymentId(UUID caseId) {
        return (UUID) payment(caseId).get("id");
    }

    private String key(UUID caseId) {
        return paymentId(caseId) + ":1";
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256="
                    + HexFormat.of()
                            .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertTuple(UUID caseId, String payment, String outbox, String caseStatus) {
        assertThat((String) payment(caseId).get("status")).as("payment status").isEqualTo(payment);
        assertThat((String) outbox(caseId).get("status")).as("outbox status").isEqualTo(outbox);
        assertThat(caseStatus(caseId)).as("case status").isEqualTo(caseStatus);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }
}
