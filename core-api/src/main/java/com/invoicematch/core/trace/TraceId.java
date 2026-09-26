package com.invoicematch.core.trace;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates and generates request trace ids.
 *
 * <p>A client may supply {@code X-Trace-Id}, but it is only accepted when it is
 * a short token of safe characters. Oversized values, control characters,
 * whitespace and empty strings are replaced with a generated id instead of
 * being trusted, so a hostile header cannot pollute logs or audit rows.
 */
public final class TraceId {

    public static final String HEADER = "X-Trace-Id";
    public static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}");

    private TraceId() {
    }

    /** Returns the candidate when valid, otherwise a generated trace id. */
    public static String resolve(String candidate) {
        if (candidate != null && ALLOWED.matcher(candidate).matches()) {
            return candidate;
        }
        return generate();
    }

    public static String generate() {
        return "trc-" + UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isValid(String candidate) {
        return candidate != null && ALLOWED.matcher(candidate).matches();
    }
}
