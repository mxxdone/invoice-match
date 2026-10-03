package com.invoicematch.core.analysis.infrastructure;

import com.invoicematch.core.analysis.application.AnalysisPublishCommand;
import com.invoicematch.core.analysis.application.AnalysisPublishError;
import com.invoicematch.core.analysis.application.AnalysisPublishResult;
import com.invoicematch.core.analysis.application.AnalysisRelayProperties;
import com.invoicematch.core.analysis.application.AnalysisRequestPublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RabbitMQ adapter for the analysis-request publisher port. It owns exactly one
 * bounded execution slot: a single CAS admits at most one attempt, and a second
 * concurrent or post-close call is rejected immediately with a fixed code rather
 * than queued. Each attempt owns its own cancellation flag and connection
 * handle, so a caller timeout or {@link #close()} aborts only that attempt and
 * can never touch a later one; the slot is released only when the attempt's
 * worker actually finishes.
 *
 * <p>An attempt creates and tears down its own connection and channel, declares
 * the durable exchange/queue/binding, publishes a persistent UTF-8 JSON message
 * with {@code mandatory=true} and {@code messageId=eventId}, and reports success
 * only on a publisher confirm ACK with no mandatory return. The whole attempt
 * (connect, declare, publish, confirm) is bounded by a monotonic deadline; on
 * deadline the attempt flag is set and its connection aborted to break the I/O,
 * and a connection that completes after the deadline is closed by that same
 * flag. The Java client processes {@code basic.return} and the following
 * {@code basic.ack} synchronously in wire order on the connection reader thread,
 * so a confirm ACK guarantees any preceding mandatory return was already
 * observed — no sleep-based settle is used.
 *
 * <p>Teardown uses the SDK's bounded {@code abort(int,...)}/{@code close(...,
 * timeout)} overloads under a small cleanup budget, and executor shutdown is
 * bounded. A cleanup that does not complete is surfaced as a fixed
 * {@code CLEANUP_FAILED}. Automatic connection/topology recovery is disabled, so
 * only the relay republishes. No payload, credential or SDK exception message is
 * logged or returned.
 */
public class RabbitAnalysisRequestPublisher implements AnalysisRequestPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitAnalysisRequestPublisher.class);
    private static final int RPC_TIMEOUT_MILLIS = 3000;
    private static final int CLEANUP_BUDGET_MILLIS = 500;
    private static final long EXECUTOR_SHUTDOWN_MILLIS = 2000;
    private static final String EXCHANGE_TYPE = BuiltinExchangeType.DIRECT.getType();
    private static final String EVENT_TYPE = "InvoiceAnalysisRequested";
    private static final String CLEANUP_MESSAGE = "analysis relay cleanup";

    private final AnalysisRelayProperties properties;
    private final ExecutorService attempts = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "analysis-relay-publisher");
        thread.setDaemon(true);
        return thread;
    });
    /** Admission guard: at most one live attempt; never released on caller timeout. */
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Attempt> current = new AtomicReference<>();

    public RabbitAnalysisRequestPublisher(AnalysisRelayProperties properties) {
        this.properties = properties;
    }

    /**
     * Test seam: runs after a real connection is owned and before topology is
     * declared. Production leaves it a no-op; package-private so it is not part
     * of the adapter's public surface.
     */
    AnalysisPublishHooks hooks = AnalysisPublishHooks.NONE;

    @Override
    public AnalysisPublishResult publish(AnalysisPublishCommand command) {
        if (closed.get()) {
            return new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED);
        }
        if (!busy.compareAndSet(false, true)) {
            return new AnalysisPublishResult.Failed(AnalysisPublishError.SLOT_BUSY);
        }
        Attempt attempt = new Attempt(command);
        current.set(attempt);
        Future<AnalysisPublishResult> future;
        try {
            future = attempts.submit(() -> execute(attempt));
        } catch (RejectedExecutionException e) {
            current.compareAndSet(attempt, null);
            busy.set(false);
            return new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED);
        }
        try {
            return future.get(AnalysisRelayProperties.ATTEMPT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            attempt.cancelAndAbort();
            return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt.cancelAndAbort();
            return new AnalysisPublishResult.Failed(AnalysisPublishError.IO_FAILED);
        } catch (ExecutionException e) {
            attempt.cancelAndAbort();
            return new AnalysisPublishResult.Failed(classify(e.getCause()));
        }
        // No finally: the slot is owned and released by the worker's own finally,
        // so a caller timeout can never free the slot while the socket is live.
    }

    private AnalysisPublishResult execute(Attempt attempt) {
        try {
            return attempt.run();
        } finally {
            current.compareAndSet(attempt, null);
            busy.set(false);
        }
    }

    private ConnectionFactory connectionFactory() {
        AnalysisRelayProperties.Rabbit rabbit = properties.rabbit();
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbit.host());
        factory.setPort(rabbit.port());
        factory.setVirtualHost(rabbit.vhost());
        factory.setUsername(rabbit.username());
        factory.setPassword(rabbit.password());
        factory.setConnectionTimeout(RPC_TIMEOUT_MILLIS);
        factory.setHandshakeTimeout(RPC_TIMEOUT_MILLIS);
        factory.setChannelRpcTimeout(RPC_TIMEOUT_MILLIS);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        return factory;
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Attempt attempt = current.get();
        if (attempt != null) {
            attempt.cancelAndAbort();
        }
        attempts.shutdownNow();
        try {
            if (!attempts.awaitTermination(EXECUTOR_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("analysis relay publisher executor did not terminate within {}ms",
                        EXECUTOR_SHUTDOWN_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static AnalysisPublishError classify(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof UnknownHostException
                    || cause instanceof ConnectException
                    || cause instanceof java.net.NoRouteToHostException
                    || cause instanceof java.net.SocketException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof com.rabbitmq.client.AuthenticationFailureException
                    || cause instanceof com.rabbitmq.client.PossibleAuthenticationFailureException
                    || cause instanceof com.rabbitmq.client.ShutdownSignalException
                    || cause instanceof IOException) {
                return AnalysisPublishError.CONNECT_FAILED;
            }
            cause = cause.getCause();
        }
        return AnalysisPublishError.IO_FAILED;
    }

    /**
     * Independent per-attempt state: its own cancellation flag, its own
     * connection handle and its own channel. Aborting an attempt can never affect
     * another attempt.
     */
    private final class Attempt {

        private final AnalysisPublishCommand command;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final long start = System.nanoTime();
        private Channel channel;

        private Attempt(AnalysisPublishCommand command) {
            this.command = command;
        }

        void cancelAndAbort() {
            cancelled.set(true);
            abortConnection();
        }

        private void abortConnection() {
            Connection live = connection.getAndSet(null);
            if (live != null) {
                safeAbort(live);
            }
        }

        AnalysisPublishResult run() {
            AnalysisPublishResult result;
            try {
                result = doPublish();
            } catch (Exception e) {
                result = cancelled.get() || pastDeadline()
                        ? new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT)
                        : new AnalysisPublishResult.Failed(classify(e));
            }
            if (!cleanup()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.CLEANUP_FAILED);
            }
            return result;
        }

        private AnalysisPublishResult doPublish() throws Exception {
            if (pastDeadline()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            AnalysisRelayProperties.Rabbit rabbit = properties.rabbit();
            Connection live = connectionFactory().newConnection();
            if (cancelled.get() || pastDeadline()) {
                safeAbort(live);
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            connection.set(live);

            channel = live.createChannel();
            channel.confirmSelect();
            channel.exchangeDeclare(rabbit.exchange(), EXCHANGE_TYPE, true);
            channel.queueDeclare(rabbit.queue(), true, false, false, null);
            channel.queueBind(rabbit.queue(), rabbit.exchange(), rabbit.routingKey());

            hooks.afterTopology(live);
            if (cancelled.get() || pastDeadline()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }

            AtomicBoolean returned = new AtomicBoolean(false);
            AtomicBoolean nacked = new AtomicBoolean(false);
            channel.addReturnListener(
                    (replyCode, replyText, exchange, routingKey, basicProperties, body) -> returned.set(true));
            channel.addConfirmListener(
                    (deliveryTag, multiple) -> {
                    },
                    (deliveryTag, multiple) -> nacked.set(true));

            AMQP.BasicProperties messageProperties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding(StandardCharsets.UTF_8.name())
                    .deliveryMode(2)
                    .messageId(command.eventId().toString())
                    .type(EVENT_TYPE)
                    .build();
            byte[] body = command.payload().getBytes(StandardCharsets.UTF_8);
            channel.basicPublish(rabbit.exchange(), rabbit.routingKey(), true, messageProperties, body);

            long remainingNanos = AnalysisRelayProperties.ATTEMPT_DEADLINE.toNanos()
                    - (System.nanoTime() - start);
            if (remainingNanos <= 0) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            // waitForConfirms(0) would mean wait forever; clamp to at least 1ms.
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            boolean confirmed;
            try {
                confirmed = channel.waitForConfirms(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AnalysisPublishResult.Failed(AnalysisPublishError.IO_FAILED);
            }
            if (!confirmed) {
                return new AnalysisPublishResult.Failed(
                        nacked.get() ? AnalysisPublishError.CONFIRM_NACK : AnalysisPublishError.CONFIRM_TIMEOUT);
            }
            // The client processes a mandatory basic.return before the following
            // basic.ack on the same reader thread, so an ACK implies any return
            // was already delivered to the listener.
            if (returned.get()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.MANDATORY_RETURN);
            }
            return new AnalysisPublishResult.Published();
        }

        private boolean cleanup() {
            boolean ok = true;
            if (channel != null) {
                try {
                    channel.abort(AMQP.REPLY_SUCCESS, CLEANUP_MESSAGE);
                } catch (Exception e) {
                    ok = false;
                }
            }
            Connection live = connection.getAndSet(null);
            if (live != null) {
                ok = closeConnection(live) && ok;
            }
            return ok;
        }

        private boolean closeConnection(Connection connection) {
            try {
                if (cancelled.get()) {
                    connection.abort(AMQP.REPLY_SUCCESS, CLEANUP_MESSAGE, CLEANUP_BUDGET_MILLIS);
                } else {
                    connection.close(AMQP.REPLY_SUCCESS, CLEANUP_MESSAGE, CLEANUP_BUDGET_MILLIS);
                }
                return true;
            } catch (Exception e) {
                safeAbort(connection);
                return false;
            }
        }

        private boolean pastDeadline() {
            return System.nanoTime() - start >= AnalysisRelayProperties.ATTEMPT_DEADLINE.toNanos();
        }
    }

    private static void safeAbort(Connection connection) {
        try {
            connection.abort(AMQP.REPLY_SUCCESS, CLEANUP_MESSAGE, CLEANUP_BUDGET_MILLIS);
        } catch (Exception e) {
            // best-effort teardown only
        }
    }

    /**
     * Package-private test seam invoked after a real connection is owned and the
     * topology is declared/bound and before the publish, so a test can hold an
     * attempt past the outer deadline or unbind the routing key to force an
     * ACK+return.
     */
    interface AnalysisPublishHooks {
        AnalysisPublishHooks NONE = connection -> {
        };

        void afterTopology(Connection connection) throws Exception;
    }
}
