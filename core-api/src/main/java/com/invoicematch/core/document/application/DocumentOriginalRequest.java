package com.invoicematch.core.document.application;

/** Only constructed from registered, frozen metadata by the application. */
public record DocumentOriginalRequest(String objectKey, String mediaType, long sizeBytes, String checksum) { }
