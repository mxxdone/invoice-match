package com.invoicematch.core.trace;

/**
 * Per-request trace id holder. The filter sets it before the security chain and
 * clears it in a {@code finally} block, so a pooled worker thread never leaks a
 * previous request's id. Non-HTTP business writes get a generated id on demand.
 */
public final class TraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        CURRENT.set(traceId);
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
