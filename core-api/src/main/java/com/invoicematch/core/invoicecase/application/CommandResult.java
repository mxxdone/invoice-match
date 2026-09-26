package com.invoicematch.core.invoicecase.application;

/**
 * A write outcome and the HTTP status it should be returned with. Persisting
 * the status with the idempotency record lets a replayed request reproduce the
 * original response exactly.
 */
public record CommandResult<T>(int status, T body) {

    public static <T> CommandResult<T> created(T body) {
        return new CommandResult<>(201, body);
    }

    public static <T> CommandResult<T> ok(T body) {
        return new CommandResult<>(200, body);
    }
}
