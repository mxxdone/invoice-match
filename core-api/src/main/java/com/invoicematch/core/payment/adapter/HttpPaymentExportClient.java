package com.invoicematch.core.payment.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Direct HTTP adapter for the Phase 1 in-process relay, built on the JDK
 * {@link HttpClient} so a single {@link HttpRequest#timeout(Duration)} bounds the
 * whole request (connect, response headers, body and completion), not just
 * individual reads. The response body is discarded
 * ({@code BodyHandlers.discarding()}), so an oversized response is never
 * buffered and a definite status cannot be turned into a false unknown.
 *
 * <p>Transport failures and 5xx are surfaced as
 * {@link PaymentExportOutcome.ResultUnknown} because a side effect may already
 * have occurred; only explicit 429 is retryable.
 */
@Component
public class HttpPaymentExportClient implements PaymentExportClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentExportClient.class);
    private static final String EXPORT_PATH = "/api/payment-exports";

    private final HttpClient httpClient;
    private final PaymentExportProperties properties;

    public HttpPaymentExportClient(HttpClient paymentExportHttpClient, PaymentExportProperties properties) {
        this.httpClient = paymentExportHttpClient;
        this.properties = properties;
    }

    @Override
    public PaymentExportOutcome send(PaymentExportRequest request) {
        URI uri;
        try {
            uri = URI.create(properties.relay().baseUrl() + EXPORT_PATH);
        } catch (IllegalArgumentException e) {
            return new PaymentExportOutcome.ResultUnknown(null, "CONFIG", "invalid ERP endpoint configuration");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(properties.relay().requestTimeout())
                .header("Idempotency-Key", request.idempotencyKey())
                .header("X-Payment-Request-Id", request.paymentRequestId().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.payload(), StandardCharsets.UTF_8))
                .build();
        // Bound the whole exchange, including a trickled response body, by
        // waiting on the async completion with a real total deadline. The JDK
        // request timeout alone only covers response headers.
        CompletableFuture<HttpResponse<Void>> future =
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.discarding());
        try {
            HttpResponse<Void> response = future.get(properties.relay().requestTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            return classify(response.statusCode());
        } catch (TimeoutException e) {
            future.cancel(true);
            return new PaymentExportOutcome.ResultUnknown(null, "TIMEOUT", "ERP request exceeded the total deadline");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                return new PaymentExportOutcome.ResultUnknown(
                        null, "TIMEOUT", "ERP request exceeded the total deadline");
            }
            log.debug("payment export {} transport failure", request.paymentRequestId(), cause);
            return new PaymentExportOutcome.ResultUnknown(
                    null, "TRANSPORT", "transport failure: "
                            + (cause == null ? "unknown" : cause.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new PaymentExportOutcome.ResultUnknown(null, "INTERRUPTED", "ERP request was interrupted");
        }
    }

    private PaymentExportOutcome classify(int status) {
        if (status >= 200 && status < 300) {
            return new PaymentExportOutcome.Acknowledged(status);
        }
        if (status == 429) {
            return new PaymentExportOutcome.RateLimited(
                    status, "RATE_LIMITED", "ERP rate limited the export", null);
        }
        if (status >= 500) {
            return new PaymentExportOutcome.ResultUnknown(
                    status, "HTTP_" + status, "ERP returned " + status + " and the outcome is not certain");
        }
        if (status >= 400) {
            return new PaymentExportOutcome.NonRetryable(
                    status, "HTTP_" + status, "ERP rejected the export with " + status);
        }
        return new PaymentExportOutcome.ResultUnknown(status, "HTTP_" + status, "unexpected ERP status " + status);
    }
}
