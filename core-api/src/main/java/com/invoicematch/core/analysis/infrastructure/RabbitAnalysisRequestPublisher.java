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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
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
import javax.net.SocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RabbitMQ adapter for the analysis-request publisher port. It owns exactly one
 * bounded execution slot: a single CAS admits at most one attempt, and a second
 * concurrent or post-close call is rejected immediately with a fixed code rather
 * than queued. The slot is released only when the attempt's worker actually
 * finishes.
 *
 * <p>Each attempt owns its own raw TCP socket. The connection factory is built
 * per attempt with a {@link SocketFactory} that creates a fresh
 * {@link Socket} and registers it on the attempt <em>before</em> the SDK
 * connects it; the blocking {@code SocketFrameHandlerFactory} uses exactly this
 * socket for that one connection. A caller timeout/interruption/close therefore
 * only sets the attempt's flag and closes that attempt's raw socket on the
 * caller's thread — it never calls an SDK abort/close/channel RPC, so a blocked
 * close-frame write can never make the caller wait. Closing the socket breaks
 * connect/read/write/close-frame write, so the worker's own finally can run and
 * release the slot. The raw socket handle stays owned even when the SDK
 * connection reference is cleared, and an unexpected socket replacement closes
 * the previous owned socket; sockets are never shared between attempts.
 *
 * <p>The worker registers the connection and re-checks
 * closed/cancelled/deadline before any channel or topology I/O, so a close or
 * timeout racing connection creation aborts the late handle and never starts
 * broker I/O. It then declares the durable exchange/queue/binding and publishes
 * a persistent UTF-8 JSON message with {@code mandatory=true} and
 * {@code messageId=eventId}; success is only reported on a publisher confirm ACK
 * with no mandatory return. The whole attempt (connect, declare, publish,
 * confirm) is bounded by a monotonic deadline. Worker teardown closes the raw
 * socket first and only then performs the bounded SDK abort on the now-closed
 * connection; a teardown failure is surfaced as a fixed {@code CLEANUP_FAILED}
 * and logged. The Java client processes {@code basic.return} and the following
 * {@code basic.ack} synchronously in wire order on the connection reader thread,
 * so a confirm ACK guarantees any preceding mandatory return was already
 * observed. Automatic connection/topology recovery is disabled, so only the
 * relay republishes. No payload, credential or SDK exception message is logged
 * or returned.
 */
public class RabbitAnalysisRequestPublisher implements AnalysisRequestPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitAnalysisRequestPublisher.class);
    private static final int RPC_TIMEOUT_MILLIS = 3000;
    private static final int CLEANUP_BUDGET_MILLIS = 500;
    private static final long TOTAL_SHUTDOWN_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2);
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
            attempt.abortOnCallerThread();
            return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt.abortOnCallerThread();
            return new AnalysisPublishResult.Failed(AnalysisPublishError.IO_FAILED);
        } catch (ExecutionException e) {
            attempt.abortOnCallerThread();
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

    private ConnectionFactory connectionFactory(Attempt attempt) {
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
        factory.setSocketFactory(new AttemptSocketFactory(attempt));
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
            attempt.abortOnCallerThread();
        }
        attempts.shutdownNow();
        long deadline = System.nanoTime() + TOTAL_SHUTDOWN_BUDGET_NANOS;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            log.warn("analysis relay publisher shutdown had no budget left");
            return;
        }
        try {
            if (!attempts.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                log.warn("analysis relay publisher worker did not terminate within the 2s shutdown budget");
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

    /** Creates and registers the one raw socket this attempt may ever use. */
    private final class AttemptSocketFactory extends SocketFactory {

        private final Attempt attempt;

        private AttemptSocketFactory(Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket socket = new Socket();
            attempt.registerSocket(socket);
            if (closed.get() || attempt.isAborted()) {
                closeRaw(socket);
                throw new IOException("analysis relay attempt aborted before connect");
            }
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connect(createSocket(), new InetSocketAddress(host, port), null);
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws IOException {
            return connect(createSocket(), new InetSocketAddress(host, port),
                    new InetSocketAddress(localHost, localPort));
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            return connect(createSocket(), new InetSocketAddress(host, port), null);
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress,
                int localPort) throws IOException {
            return connect(createSocket(), new InetSocketAddress(address, port),
                    new InetSocketAddress(localAddress, localPort));
        }

        private Socket connect(Socket socket, SocketAddress remote, SocketAddress local) throws IOException {
            try {
                if (local != null) {
                    socket.bind(local);
                }
                socket.connect(remote, RPC_TIMEOUT_MILLIS);
                return socket;
            } catch (IOException e) {
                closeRaw(socket);
                throw e;
            }
        }
    }

    /**
     * Independent per-attempt state: its own cancellation flag, its own raw
     * socket and its own (optional) SDK connection handle. Aborting an attempt
     * can never affect another attempt.
     */
    private final class Attempt {

        private final AnalysisPublishCommand command;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Socket> socket = new AtomicReference<>();
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final long start = System.nanoTime();

        private Attempt(AnalysisPublishCommand command) {
            this.command = command;
        }

        /**
         * Caller-side abort: set the flag and close only this attempt's raw
         * socket. No SDK abort/close/channel RPC runs on the caller thread, so a
         * blocked close-frame write cannot delay the caller.
         */
        void abortOnCallerThread() {
            cancelled.set(true);
            Socket raw = socket.get();
            if (raw != null) {
                closeRaw(raw);
            }
        }

        private boolean isAborted() {
            return cancelled.get() || pastDeadline();
        }

        private void registerSocket(Socket raw) {
            Socket previous = socket.getAndSet(raw);
            if (previous != null && previous != raw) {
                closeRaw(previous);
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
                log.warn("analysis relay attempt cleanup failed");
                return new AnalysisPublishResult.Failed(AnalysisPublishError.CLEANUP_FAILED);
            }
            return result;
        }

        private AnalysisPublishResult doPublish() throws Exception {
            if (closed.get()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED);
            }
            if (isAborted()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }
            AnalysisRelayProperties.Rabbit rabbit = properties.rabbit();
            Connection candidate = connectionFactory(this).newConnection();
            connection.set(candidate);
            if (closed.get() || isAborted()) {
                return closed.get()
                        ? new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED)
                        : new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }

            hooks.afterConnectionCreated(candidate);
            if (closed.get() || isAborted()) {
                return closed.get()
                        ? new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_CLOSED)
                        : new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }

            Channel channel = candidate.createChannel();
            channel.confirmSelect();
            channel.exchangeDeclare(rabbit.exchange(), EXCHANGE_TYPE, true);
            String queue=command.deadLetter() ? rabbit.queue()+".dlq" : rabbit.queue();
            String routingKey=command.deadLetter() ? rabbit.routingKey()+".dlq" : rabbit.routingKey();
            channel.queueDeclare(queue, true, false, false, null);
            channel.queueBind(queue, rabbit.exchange(), routingKey);

            hooks.afterTopology(candidate);
            if (closed.get() || isAborted()) {
                return new AnalysisPublishResult.Failed(AnalysisPublishError.PUBLISH_TIMEOUT);
            }

            AtomicBoolean returned = new AtomicBoolean(false);
            AtomicBoolean nacked = new AtomicBoolean(false);
            channel.addReturnListener(
                    (replyCode, replyText, exchange, returnedRoutingKey, basicProperties, body) -> returned.set(true));
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
            channel.basicPublish(rabbit.exchange(), routingKey, true, messageProperties, body);

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

        /**
         * Worker-side teardown: close the owned raw socket first (which also
         * breaks any in-flight I/O), then perform the bounded SDK abort on the
         * now-closed connection only on this worker thread.
         */
        private boolean cleanup() {
            boolean ok = true;
            Socket raw = socket.getAndSet(null);
            if (raw != null) {
                ok = closeRaw(raw);
            }
            Connection live = connection.getAndSet(null);
            if (live != null) {
                try {
                    live.abort(AMQP.REPLY_SUCCESS, CLEANUP_MESSAGE, CLEANUP_BUDGET_MILLIS);
                } catch (com.rabbitmq.client.AlreadyClosedException e) {
                    // already shut down; nothing to reap
                } catch (Exception e) {
                    ok = false;
                }
            }
            return ok;
        }

        private boolean pastDeadline() {
            return System.nanoTime() - start >= AnalysisRelayProperties.ATTEMPT_DEADLINE.toNanos();
        }
    }

    private static boolean closeRaw(Socket socket) {
        try {
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Package-private test seam. {@code afterConnectionCreated} is invoked after a
     * real connection is registered and re-checked (so a test can race a
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
