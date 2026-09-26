package com.invoicematch.core.invoicecase.api;

/**
 * Minimal error body returned by the invoice case API. Stable machine-readable
 * {@code code} plus a human message.
 */
public record ApiError(String code, String message) {
}
