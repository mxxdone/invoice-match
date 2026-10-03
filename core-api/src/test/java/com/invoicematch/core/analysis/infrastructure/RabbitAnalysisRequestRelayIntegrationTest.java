package com.invoicematch.core.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.analysis.AbstractAnalysisRelayIntegrationTest;
import com.invoicematch.core.analysis.application.AnalysisPublishCommand;
import com.invoicematch.core.analysis.application.AnalysisPublishError;
import com.invoicematch.core.analysis.application.AnalysisPublishResult;
import com.invoicematch.core.analysis.application.AnalysisRelayProperties;
import com.invoicematch.core.analysis.application.AnalysisRequestRelay;
import com.invoicematch.core.analysis.persistence.AnalysisOutboxStore;
import com.invoicematch.core.analysis.persistence.ClaimedAnalysisRequest;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * P2-06 relay against a real RabbitMQ ({@code rabbitmq:4.2-alpine}) and real
 * PostgreSQL: a reservation is published with the stable event id and persistent
 * JSON metadata and then marked PUBLISHED; an unroutable mandatory message is
 * ACKed+returned and classified as a failure; the single execution slot rejects
 * busy calls without queueing and recovers after an outer-deadline abort; and a
 * confirmed publish whose worker dies before DB finalization is re-published
 * with the same event id (at-least-once, never exactly-once).
 */
class RabbitAnalysisRequestRelayIntegrationTest extends AbstractAnalysisRelayIntegrationTest {

    private static final String RABBIT_USER = "im-relay-test";
    private static final String RABBIT_PASSWORD = "im-relay-test-secret";
    private static final String EXCHANGE = "invoice.analysis";
    private static final String QUEUE = "invoice.analysis.requests";
    private static final String ROUTING_KEY = "document-parser-v1";

    private static final GenericContainer<?> RABBIT;

    static {
        RABBIT = new GenericContainer<>(DockerImageName.parse("rabbitmq:4.2-alpine"))
                .withEnv("RABBITMQ_DEFAULT_USER", RABBIT_USER)
                .withEnv("RABBITMQ_DEFAULT_PASS", RABBIT_PASSWORD)
                .withExposedPorts(5672)
                .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                .withStartupTimeout(Duration.ofMinutes(3));
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("analysis.relay.enabled", () -> true);
        registry.add("analysis.relay.rabbit.host", RABBIT::getHost);
        registry.add("analysis.relay.rabbit.port", () -> RABBIT.getMappedPort(5672));
        registry.add("analysis.relay.rabbit.username", () -> RABBIT_USER);
        registry.add("analysis.relay.rabbit.password", () -> RABBIT_PASSWORD);
    }

    @Autowired
    AnalysisRequestRelay relay;

    @Autowired
    AnalysisOutboxStore store;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void publishesReservationWithStableEventIdAndPersistentMetadata() throws Exception {
        purgeQueue();
        UUID caseId = createDraftCase("INV-Q1");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-q1");
        UUID eventId = outboxId(caseId, 1);
        String payload = (String) outboxRow(caseId, 1).get("payload");

        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("PUBLISHED");

        try (Connection connection = rawFactory().newConnection();
                Channel channel = connection.createChannel()) {
            GetResponse message = channel.basicGet(QUEUE, true);
            assertThat(message).isNotNull();
            AMQP.BasicProperties properties = message.getProps();
            assertThat(properties.getMessageId()).isEqualTo(eventId.toString());
            assertThat(properties.getType()).isEqualTo("InvoiceAnalysisRequested");
            assertThat(properties.getContentType()).isEqualTo("application/json");
            assertThat(properties.getDeliveryMode()).isEqualTo(2);
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
            assertThat(channel.basicGet(QUEUE, true)).isNull();
        }
    }

    @Test
    void unroutableMandatoryMessageIsAckedAndReturnedAsFailure() throws Exception {
        purgeQueue();
        UUID caseId = createDraftCase("INV-Q2");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-q2");

        RabbitAnalysisRequestPublisher publisher =
                applicationContext.getBean(RabbitAnalysisRequestPublisher.class);
        publisher.hooks = connection -> {
            try (Channel channel = connection.createChannel()) {
                channel.queueUnbind(QUEUE, EXCHANGE, ROUTING_KEY);
            }
        };
        try {
            assertThat(relay.runOnce()).isZero();
        } finally {
            publisher.hooks = RabbitAnalysisRequestPublisher.AnalysisPublishHooks.NONE;
        }

        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("READY");
        assertThat(outboxRow(caseId, 1).get("last_error_code")).isEqualTo("MANDATORY_RETURN");
    }

    @Test
    void unavailableBrokerFailsClosedAndClassifiesTheConnectionError() {
        RabbitAnalysisRequestPublisher publisher = new RabbitAnalysisRequestPublisher(propertiesTo("127.0.0.1", 1));
        try {
            AnalysisPublishResult result =
                    publisher.publish(new AnalysisPublishCommand(UUID.randomUUID(), "{\"k\":1}"));
            assertThat(result).isInstanceOf(AnalysisPublishResult.Failed.class);
            assertThat(((AnalysisPublishResult.Failed) result).error())
                    .isEqualTo(AnalysisPublishError.CONNECT_FAILED);
        } finally {
            publisher.close();
        }
    }

    @Test
    void stalledHandshakeIsBoundedAndClassified() throws Exception {
        StalledTcpServer stalled = new StalledTcpServer();
        RabbitAnalysisRequestPublisher publisher =
                new RabbitAnalysisRequestPublisher(propertiesTo("127.0.0.1", stalled.port()));
        try {
            long start = System.nanoTime();
            AnalysisPublishResult result =
                    publisher.publish(new AnalysisPublishCommand(UUID.randomUUID(), "{\"k\":1}"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(result).isInstanceOf(AnalysisPublishResult.Failed.class);
            assertThat(((AnalysisPublishResult.Failed) result).error())
                    .isEqualTo(AnalysisPublishError.CONNECT_FAILED);
            assertThat(elapsed).isLessThan(AnalysisRelayProperties.ATTEMPT_DEADLINE.plusSeconds(3));
        } finally {
            stalled.close();
            publisher.close();
        }
    }

    @Test
    void outerDeadlineAbortsBusyCallsAreRejectedAndCloseRefusesNewOnes() throws Exception {
        RabbitAnalysisRequestPublisher publisher = new RabbitAnalysisRequestPublisher(realBrokerProperties());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        publisher.hooks = connection -> {
            entered.countDown();
            release.await();
        };
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outer-deadline-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<AnalysisPublishResult> first = pool.submit(() ->
                    publisher.publish(new AnalysisPublishCommand(UUID.randomUUID(), "{\"probe\":1}")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("a real connection is owned").isTrue();

            long busyStart = System.nanoTime();
            assertThat(failureOf(publisher.publish(
                            new AnalysisPublishCommand(UUID.randomUUID(), "{\"busy\":1}"))))
                    .isEqualTo(AnalysisPublishError.SLOT_BUSY);
            assertThat(Duration.ofNanos(System.nanoTime() - busyStart))
                    .as("a busy call must be rejected immediately, not queued")
                    .isLessThan(Duration.ofSeconds(1));

            // The worker is held past the 10s outer deadline; the caller times out.
            AnalysisPublishResult firstResult = first.get(13, TimeUnit.SECONDS);
            assertThat(failureOf(firstResult)).isEqualTo(AnalysisPublishError.PUBLISH_TIMEOUT);
            // Still busy until the worker itself finishes: a cancelling attempt
            // does not free the slot.
            assertThat(failureOf(publisher.publish(
                            new AnalysisPublishCommand(UUID.randomUUID(), "{\"busy2\":1}"))))
                    .isEqualTo(AnalysisPublishError.SLOT_BUSY);

            release.countDown();

            // The slot recovers and a real broker publish then succeeds.
            AnalysisPublishResult recovered = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                AnalysisPublishResult attempt = publisher.publish(
                        new AnalysisPublishCommand(UUID.randomUUID(), "{\"recovered\":1}"));
                if (attempt instanceof AnalysisPublishResult.Published) {
                    recovered = attempt;
                    break;
                }
                assertThat(failureOf(attempt)).isEqualTo(AnalysisPublishError.SLOT_BUSY);
                Thread.sleep(25);
            }
            assertThat(recovered).as("the slot must recover to a successful publish")
                    .isInstanceOf(AnalysisPublishResult.Published.class);

            publisher.close();
            assertThat(failureOf(publisher.publish(
                            new AnalysisPublishCommand(UUID.randomUUID(), "{\"closed\":1}"))))
                    .isEqualTo(AnalysisPublishError.RELAY_CLOSED);
        } finally {
            release.countDown();
            pool.shutdownNow();
            publisher.close();
        }
    }

    @Test
    void confirmBeforeFinalizeCrashRepublishesTheSameEventId() throws Exception {
        purgeQueue();
        UUID caseId = createDraftCase("INV-Q3");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-q3");
        UUID eventId = outboxId(caseId, 1);
        String payload = (String) outboxRow(caseId, 1).get("payload");

        RabbitAnalysisRequestPublisher publisher =
                applicationContext.getBean(RabbitAnalysisRequestPublisher.class);
        // A worker claims with a real short lease, reaches the broker (confirmed),
        // then dies before the DB finalization.
        ClaimedAnalysisRequest claimed = store.claimOne("crash-worker", Duration.ofSeconds(1)).orElseThrow();
        assertThat(claimed.eventId()).isEqualTo(eventId);
        assertThat(publisher.publish(new AnalysisPublishCommand(eventId, payload)))
                .isInstanceOf(AnalysisPublishResult.Published.class);
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("CLAIMED");

        awaitLeaseExpiry(eventId);
        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("PUBLISHED");

        try (Connection connection = rawFactory().newConnection();
                Channel channel = connection.createChannel()) {
            GetResponse first = channel.basicGet(QUEUE, true);
            GetResponse second = channel.basicGet(QUEUE, true);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.getProps().getMessageId()).isEqualTo(eventId.toString());
            assertThat(second.getProps().getMessageId()).isEqualTo(eventId.toString());
            assertThat(new String(first.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
            assertThat(new String(second.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
            assertThat(channel.basicGet(QUEUE, true)).isNull();
        }
    }

    private AnalysisPublishError failureOf(AnalysisPublishResult result) {
        assertThat(result).isInstanceOf(AnalysisPublishResult.Failed.class);
        return ((AnalysisPublishResult.Failed) result).error();
    }

    private AnalysisRelayProperties propertiesTo(String host, int port) {
        return new AnalysisRelayProperties(
                false,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                AnalysisRelayProperties.MAX_TICK_BATCH,
                Duration.ofSeconds(5),
                new AnalysisRelayProperties.Rabbit(host, port, "/", "u", "p", EXCHANGE, QUEUE, ROUTING_KEY));
    }

    private AnalysisRelayProperties realBrokerProperties() {
        return new AnalysisRelayProperties(
                false,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                AnalysisRelayProperties.MAX_TICK_BATCH,
                Duration.ofSeconds(5),
                new AnalysisRelayProperties.Rabbit(
                        RABBIT.getHost(), RABBIT.getMappedPort(5672), "/", RABBIT_USER, RABBIT_PASSWORD,
                        EXCHANGE, QUEUE, ROUTING_KEY));
    }

    private ConnectionFactory rawFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getMappedPort(5672));
        factory.setUsername(RABBIT_USER);
        factory.setPassword(RABBIT_PASSWORD);
        return factory;
    }

    private void purgeQueue() throws Exception {
        try (Connection connection = rawFactory().newConnection();
                Channel channel = connection.createChannel()) {
            channel.queueDeclare(QUEUE, true, false, false, null);
            channel.queuePurge(QUEUE);
        }
    }

    /** A TCP server that accepts connections and never sends the AMQP greeting. */
    static final class StalledTcpServer implements AutoCloseable {

        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stalled-amqp-fixture");
            thread.setDaemon(true);
            return thread;
        });

        StalledTcpServer() throws IOException {
            server = new ServerSocket(0);
            pool.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        sockets.add(server.accept());
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException e) {
                // best-effort teardown
            }
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // best-effort teardown
                }
            }
            pool.shutdownNow();
        }
    }
}
