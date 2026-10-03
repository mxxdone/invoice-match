package com.invoicematch.core.analysis.infrastructure;

import com.invoicematch.core.analysis.application.AnalysisPublishCommand;
import com.invoicematch.core.analysis.application.AnalysisPublishError;
import com.invoicematch.core.analysis.application.AnalysisPublishResult;
import com.invoicematch.core.analysis.application.AnalysisRequestPublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
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
 * RabbitMQ adapter for the analysis-request publisher port. It owns a single
 * bounded execution slot; each attempt creates and then closes its own
 * connection and channel, declares the durable exchange/queue/binding, publishes
 * a persistent UTF-8 JSON message with {@code mandatory=true} and
 * {@code messageId=eventId}, and only reports success on a publisher confirm ACK
 * with no mandatory return.
 *
 * <p>The whole attempt (connect, declare, publish, confirm) is bounded by a
 * monotonic deadline. The SDK confirm timeout alone cannot bound the write, so
 * on deadline the slot sets a cancellation flag and aborts the owned connection
 * to break the I/O; the attempt also re-checks the flag after the connection is
 * built. Automatic connection/topology recovery is disabled, so only the relay
 * republishes. Failures are classified into fixed codes; no payload, credential
 * or SDK exception message is logged or returned.
 */
public class RabbitAnalysisRequestPublisher implements AnalysisRequestPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitAnalysisRequestPublisher.class);
    private static final int RPC_TIMEOUT_MILLIS = 3000;
    private static final String EXCHANGE_TYPE = BuiltinExchangeType.DIRECT.getType();
    private static final long RETURN_SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    private static final String EVENT_TYPE = "InvoiceAnalysisRequested";

    private final AnalysisRelayProperties properties;
    private final ExecutorService attempts = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "analysis-relay-publisher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Connection> liveConnection = new AtomicReference<>();
    private volatile boolean cancelled;

    public RabbitAnalysisRequestPublisher(AnalysisRelayProperties properties) {
        this.properties = properties;
    }

    /**
     * Test seam: runs after the topology is declared/bound and before the
     * publish. Production leaves it a no-op; package-private so it is not part
     * of the adapter's public surface.
     */
    PackageHooks hooks = PackageHooks.NONE;

    @Override
    public AnalysisPublishResult publish(AnalysisPublishCommand command) {
        cancelled = false;
        long start = System.nanoTime();
        Future<AnalysisPublishResult> attempt;
        try {
            attempt = attempts.submit(() -> runAttempt(command, start));
        } catch (RejectedExecutionException e) {
            return new AnalysisPublishResult.Failed(AnalysisPublishError.IO_FAILED);
        }
        try {
            return attempt.get(AnalysisRelayProperties.ATTEMPT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelled = true;
            attempt.cancel(true);
            abortOwnedConnection();
            return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
            attempt.cancel(true);
            abortOwnedConnection();
            return new AnalysisPublishResult.Failed(AnalysisPublishError.IO_FAILED);
        } catch (ExecutionException e) {
            abortOwnedConnection();
            return new AnalysisPublishResult.Failed(classify(e.getCause()));
        } finally {
            abortOwnedConnection();
        }
    }

    private AnalysisPublishResult runAttempt(AnalysisPublishCommand command, long start) {
        Connection connection = null;
        Channel channel = null;
        try {
            if (cancelled || pastDeadline(start)) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            AnalysisRelayProperties.Rabbit rabbit = properties.rabbit();
            connection = connectionFactory().newConnection();
            if (cancelled || pastDeadline(start)) {
                safeAbort(connection);
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            liveConnection.set(connection);

            channel = connection.createChannel();
            channel.confirmSelect();
            channel.exchangeDeclare(rabbit.exchange(), EXCHANGE_TYPE, true);
            channel.queueDeclare(rabbit.queue(), true, false, false, null);
            channel.queueBind(rabbit.queue(), rabbit.exchange(), rabbit.routingKey());

            hooks.afterTopology(connection, rabbit.exchange(), rabbit.queue(), rabbit.routingKey());
            if (cancelled || pastDeadline(start)) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }

            AtomicBoolean returned = new AtomicBoolean(false);
            AtomicBoolean nacked = new AtomicBoolean(false);
            channel.addReturnListener((replyCode, replyText, exchange, routingKey, basicProperties, body) ->
                    returned.set(true));
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

            long remaining = deadlineRemainingMillis(start);
            if (remaining <= 0) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            boolean confirmed;
            try {
                confirmed = channel.waitForConfirms(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AnalysisPublishResult.Failed(AnalysisPublishError.IO_FAILED);
            }
            if (!confirmed) {
                return new AnalysisPublishResult.Failed(
                        nacked.get() ? AnalysisPublishError.CONFIRM_NACK : AnalysisPublishError.CONFIRM_TIMEOUT);
            }
            settleReturn(returned, start);
            if (returned.get()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.MANDATORY_RETURN);
            }
            return new AnalysisPublishResult.Published();
        } catch (Exception e) {
            if (cancelled || pastDeadline(start)) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            return new AnalysisPublishResult.Failed(classify(e));
        } finally {
            liveConnection.compareAndSet(connection, null);
            closeQuietly(channel);
            closeQuietly(connection);
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

    private void settleReturn(AtomicBoolean returned, long start) {
        long until = start + RETURN_SETTLE_NANOS;
        while (!returned.get() && System.nanoTime() < until && !pastDeadline(start)) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean pastDeadline(long start) {
        return System.nanoTime() - start >= AnalysisRelayProperties.ATTEMPT_DEADLINE.toNanos();
    }

    private static long deadlineRemainingMillis(long start) {
        long remaining = AnalysisRelayProperties.ATTEMPT_DEADLINE.toNanos() - (System.nanoTime() - start);
        return remaining <= 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(remaining);
    }

    private static AnalysisPublishError classify(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof com.rabbitmq.client.AuthenticationFailureException
                    || cause instanceof com.rabbitmq.client.PossibleAuthenticationFailureException) {
                return AnalysisPublishError.CONNECT_FAILED;
            }
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof com.rabbitmq.client.ShutdownSignalException
                    || cause instanceof IOException) {
                return AnalysisPublishError.CONNECT_FAILED;
            }
            cause = cause.getCause();
        }
        return AnalysisPublishError.IO_FAILED;
    }

    private void abortOwnedConnection() {
        Connection connection = liveConnection.getAndSet(null);
        if (connection != null) {
            safeAbort(connection);
        }
    }

    private static void safeAbort(Connection connection) {
        try {
            connection.abort();
        } catch (Exception e) {
            // best-effort teardown only
        }
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                // already closed or aborted
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                // already closed or aborted
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        cancelled = true;
        abortOwnedConnection();
        attempts.shutdownNow();
        try {
            if (!attempts.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("analysis relay publisher executor did not terminate within 2s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Package-private test seam. It is invoked after the exchange/queue/binding
     * are declared and before publish, so a test can make a message unroutable
     * (unbound routing key) and prove the ACK+return failure path.
     */
    interface PackageHooks {
        PackageHooks NONE = (connection, exchange, queue, routingKey) -> {
        };

        void afterTopology(Connection connection, String exchange, String queue, String routingKey) throws Exception;
    }
}
