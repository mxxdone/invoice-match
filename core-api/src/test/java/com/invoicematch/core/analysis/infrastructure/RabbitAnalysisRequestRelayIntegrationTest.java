package com.invoicematch.core.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.analysis.AbstractAnalysisRelayIntegrationTest;
import com.invoicematch.core.analysis.application.AnalysisPublishCommand;
import com.invoicematch.core.analysis.application.AnalysisPublishError;
import com.invoicematch.core.analysis.application.AnalysisPublishResult;
import com.invoicematch.core.analysis.application.AnalysisRequestRelay;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * ACKed+returned and classified as a failure; and a stalled handshake or
 * unavailable broker is bounded, classified and does not leak the single
 * execution slot.
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
        publisher.hooks = (connection, exchange, queue, routingKey) -> {
            try (Channel channel = connection.createChannel()) {
                channel.queueUnbind(queue, exchange, routingKey);
            }
        };
        try {
            assertThat(relay.runOnce()).isZero();
        } finally {
            publisher.hooks = RabbitAnalysisRequestPublisher.PackageHooks.NONE;
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
    void stalledHandshakeIsBoundedAndTheExecutionSlotRecovers() throws Exception {
        StalledTcpServer stalled = new StalledTcpServer();
        RabbitAnalysisRequestPublisher publisher =
                new RabbitAnalysisRequestPublisher(propertiesTo("127.0.0.1", stalled.port()));
        try {
            long firstStart = System.nanoTime();
            AnalysisPublishResult first =
                    publisher.publish(new AnalysisPublishCommand(UUID.randomUUID(), "{\"k\":1}"));
            long firstElapsed = System.nanoTime() - firstStart;
            assertThat(first).isInstanceOf(AnalysisPublishResult.Failed.class);
            assertThat(Duration.ofNanos(firstElapsed))
                    .as("a stalled handshake must be bounded by the total attempt deadline")
                    .isLessThan(AnalysisRelayProperties.ATTEMPT_DEADLINE.plusSeconds(3));

            // The server is now closed; a second publish must return quickly,
            // proving the single execution slot was not left occupied.
            stalled.close();
            long secondStart = System.nanoTime();
            AnalysisPublishResult second =
                    publisher.publish(new AnalysisPublishCommand(UUID.randomUUID(), "{\"k\":1}"));
            assertThat(second).isInstanceOf(AnalysisPublishResult.Failed.class);
            assertThat(Duration.ofNanos(System.nanoTime() - secondStart))
                    .as("the execution slot must be free after a deadline abort")
                    .isLessThan(Duration.ofSeconds(5));
        } finally {
            stalled.close();
            publisher.close();
        }
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
