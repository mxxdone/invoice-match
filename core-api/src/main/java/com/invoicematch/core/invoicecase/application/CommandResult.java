package com.invoicematch.core.invoicecase.application;

/**
 * A write outcome and the HTTP status it should be returned with.
 *
 * <p>This carries an HTTP status inside an application type on purpose. Every
 * write reserves its {@code (scope, resource, actor, requestId)} idempotency
 * record and persists this status with the response values, so a replayed
 * request reproduces the stored HTTP status and response values (the body is
 * decoded back into its DTO and re-serialized by the HTTP layer). Replacing the
 * status with a transport-neutral enum would change the persisted replay
 * contract for no functional gain, so the coupling is intentionally retained
 * rather than abstracted away.
 *
 * <p>Domain entities also keep their JPA annotations intentionally: they are the
 * persistence model for this application, and introducing a parallel mapping
 * layer would duplicate the schema without removing a real dependency.
 */
public record CommandResult<T>(int status, T body) {

    public static <T> CommandResult<T> created(T body) {
        return new CommandResult<>(201, body);
    }

    public static <T> CommandResult<T> ok(T body) {
        return new CommandResult<>(200, body);
    }
}
