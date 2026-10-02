package com.invoicematch.core.document.application;

/**
 * Application-to-infrastructure signing request for one registered original.
 * The object key stays inside the application/infrastructure boundary and is
 * never serialized into an API DTO. The disposition and the short TTL are
 * decided by the application layer; the adapter signs them and reports the
 * actual expiry it produced.
 */
public record DocumentDownloadRequest(String objectKey, String fileName, String mediaType, String disposition,
        int ttlSeconds) {
}
