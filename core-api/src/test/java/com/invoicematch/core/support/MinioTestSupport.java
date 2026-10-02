package com.invoicematch.core.support;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import java.time.Duration;

/**
 * Bounded MinIO bucket bootstrap for integration tests.
 *
 * <p>The container health check can pass a moment before the S3 API reports the
 * server as initialized, so a {@code makeBucket} issued during that window fails
 * with {@code XMinioServerNotInitialized} (HTTP 503). This helper retries only
 * that transient startup condition until a bounded
 * deadline, treats an already-owned bucket as success, and fails immediately on
 * any other error so a real configuration or assertion problem is never masked.
 * It logs nothing credential-bearing.
 */
public final class MinioTestSupport {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final long RETRY_DELAY_MS = 500;

    private MinioTestSupport() {
    }

    public static void initializeBucket(MinioClient client, String bucket) {
        long deadlineNanos = System.nanoTime() + DEADLINE.toNanos();
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                return;
            } catch (ErrorResponseException e) {
                String code = e.errorResponse() == null ? "" : e.errorResponse().code();
                if ("BucketAlreadyOwnedByYou".equals(code)) {
                    return;
                }
                if (!isTransient(e)) {
                    throw new IllegalStateException("MinIO bucket initialization failed: " + code, e);
                }
            } catch (Exception e) {
                throw new IllegalStateException("MinIO bucket initialization failed", e);
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException(
                        "Timed out initializing MinIO bucket after " + attempts + " attempts");
            }
            sleep();
        }
    }

    private static boolean isTransient(ErrorResponseException e) {
        String code = e.errorResponse() == null ? "" : e.errorResponse().code();
        return "XMinioServerNotInitialized".equals(code);
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while initializing MinIO bucket", e);
        }
    }
}
