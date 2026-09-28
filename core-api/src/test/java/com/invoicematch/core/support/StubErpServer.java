package com.invoicematch.core.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal ERP export stub. It captures the exact idempotency key, the payment
 * request header and the raw canonical payload of every request, lets a test set
 * the status/body/delay of the next response, and can sleep long enough to
 * trigger the client timeout.
 */
public final class StubErpServer implements AutoCloseable {

    public record Captured(String idempotencyKey, String paymentRequestHeader, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    private volatile int status = 200;
    private volatile String body = "{\"accepted\":true}";
    private volatile Duration delay = Duration.ZERO;
    private volatile Duration trickleChunkDelay = Duration.ZERO;
    private volatile int trickleChunks = 0;

    public StubErpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/payment-exports", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        captured.add(new Captured(
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                exchange.getRequestHeaders().getFirst("X-Payment-Request-Id"),
                requestBody));
        Duration currentDelay = delay;
        if (!currentDelay.isZero()) {
            try {
                Thread.sleep(currentDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (trickleChunks > 0) {
            // Send headers immediately, then trickle the body so only a real
            // total request deadline can abort the call (per-read timeouts would
            // not).
            exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < trickleChunks; i++) {
                    out.write(("chunk-" + i + ";").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(trickleChunkDelay.toMillis());
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public void respond(int status, String body) {
        this.delay = Duration.ZERO;
        this.trickleChunks = 0;
        this.status = status;
        this.body = body;
    }

    /** Clears captured requests and the counter between tests. */
    public void reset() {
        captured.clear();
        requestCount.set(0);
        respond(200, "{\"accepted\":true}");
    }

    public void respondAfter(int status, String body, Duration delay) {
        this.trickleChunks = 0;
        this.status = status;
        this.body = body;
        this.delay = delay;
    }

    /** Sends 200 headers at once and then trickles {@code chunks} body writes. */
    public void respondTrickle(int chunks, Duration chunkDelay) {
        this.delay = Duration.ZERO;
        this.status = 200;
        this.trickleChunks = chunks;
        this.trickleChunkDelay = chunkDelay;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int requestCount() {
        return requestCount.get();
    }

    public List<Captured> captured() {
        return List.copyOf(captured);
    }

    public Captured last() {
        return captured.get(captured.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
