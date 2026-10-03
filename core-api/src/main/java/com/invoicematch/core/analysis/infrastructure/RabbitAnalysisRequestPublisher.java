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
 * <p>An attempt creates and tears down its own connection. The connection is
 * registered immediately after creation and re-checked for
 * cancellation/close/deadline before any channel or topology I/O, so a caller
 * that times out or closes while the connection is being created can never let
 * the late handle start broker I/O. The durable exchange/queue/binding are then
 * declared and a persistent UTF-8 JSON message is published with
 * {@code mandatory=true} and {@code messageId=eventId}; success is only reported
 * on a publisher confirm ACK with no mandatory return. The whole attempt
 * (connect, declare, publish, confirm) is bounded by a monotonic deadline; on
 * deadline the attempt flag is set and its connection aborted to break the I/O.
 * The Java client processes {@code basic.return} and the following
 * {@code basic.ack} synchronously in wire order on the connection reader thread,
 * so a confirm ACK guarantees any preceding mandatory return was already
 * observed — no sleep-based settle is used.
 *
 * <p>Teardown is one path: the owned connection is torn down with the SDK's
 * bounded {@code abort(code,message,timeout)} and a {@code shutdownExecutor} is
 * configured so the final socket flush is bounded by the SDK's close timeout
 * rather than a synchronous flush. The SDK's abort timeout only bounds the wait
 * for {@code connection.close-ok}; the preceding close-frame write and the final
 * flush are what the {@code shutdownExecutor} bounds, while the caller is
 * already bounded by the outer deadline and the slot is held until the worker
 * finishes (busy attempts are rejected, never queued). A teardown that does not
 * complete is surfaced as a fixed {@code CLEANUP_FAILED}. Automatic
 * connection/topology recovery is disabled, so only the relay republishes. No
 * payload, credential or SDK exception message is logged or returned.
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
    /**
     * Bounds the SDK's final socket flush during teardown (rabbitmq-java-client
     * issue #194); without it {@code SocketFrameHandler.close()} flushes
     * synchronously.
     */
    private final ExecutorService cleanupFlushes = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "analysis-relay-cleanup");
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
     * Test seam: {@code afterConnectionCreated} runs after a real connection is
     * registered and re-checked, {@code afterTopology} runs after the topology is
     * declared/bound, both before the publish. Production leaves them no-ops;
     * package-private so they are not part of the adapter's public surface.
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
        factory.setShutdownExecutor(cleanupFlushes);
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
        cleanupFlushes.shutdownNow();
        awaitTermination(attempts);
        awaitTermination(cleanupFlushes);
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)) {
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
     * Independent per-attempt state: its own cancellation flag and its own
     * connection handle. Aborting an attempt can never affect another attempt.
     */
    private final class Attempt {

        private final AnalysisPublishCommand command;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final long start = System.nanoTime();

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
            // One teardown path: the owned connection is torn down once, with the
            // bounded SDK abort; closing the connection also closes its channel.
            if (!cleanup()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.CLEANUP_FAILED);
            }
            return result;
        }

        private AnalysisPublishResult doPublish() throws Exception {
            if (closed.get()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED);
            }
            if (cancelled.get() || pastDeadline()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            AnalysisRelayProperties.Rabbit rabbit = properties.rabbit();
            Connection candidate = connectionFactory().newConnection();
            // Register immediately, then re-check: a cancel/close that raced the
            // connection creation is seen here and the live handle is aborted
            // before any channel or topology I/O begins.
            connection.set(candidate);
            if (closed.get() || cancelled.get() || pastDeadline()) {
                abortConnection();
                return closed.get()
                        ? new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED)
                        : new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }

            hooks.afterConnectionCreated(candidate);
            if (closed.get() || cancelled.get() || pastDeadline()) {
                abortConnection();
                return closed.get()
                        ? new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED)
                        : new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }

            Channel channel = candidate.createChannel();
            channel.confirmSelect();
            channel.exchangeDeclare(rabbit.exchange(), EXCHANGE_TYPE, true);
            channel.queueDeclare(rabbit.queue(), true, false, false, null);
            channel.queueBind(rabbit.queue(), rabbit.exchange(), rabbit.routingKey());

            hooks.afterTopology(candidate);
            if (closed.get() || cancelled.get() || pastDeadline()) {
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
            Connection live = connection.getAndSet(null);
            if (live == null) {
                return true;
            }
            try {
                live.abort(AMQP.REPLY_SUCCESS, CLEANUP_MESSAGE, CLEANUP_BUDGET_MILLIS);
                return true;
            } catch (com.rabbitmq.client.AlreadyClosedException e) {
                return true;
            } catch (Exception e) {
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
     * Package-private test seam. {@code afterConnectionCreated} is invoked after
     * a real connection is registered and re-checked (so a test can race a
     * cancel/close exactly at registration); {@code afterTopology} is invoked
     * after the topology is declared/bound and before the publish (so a test can
     * unbind the routing key or hold the attempt past the outer deadline).
     */
    interface AnalysisPublishHooks {

        AnalysisPublishHooks NONE = new AnalysisPublishHooks() {
        };

        default void afterConnectionCreated(Connection connection) throws Exception {
        }

        default void afterTopology(Connection connection) throws Exception {
        }
    }
}
