package com.invoicematch.core.document.application;

import java.time.Instant;

/**
 * Result of signing a browser-facing original GET. The expiry is derived from
 * the signed URL's own signing date and requested lifetime, so the API DTO and
 * the capability always expire at the same instant.
 */
public record SignedDownload(String url, Instant expiresAt) {
}
