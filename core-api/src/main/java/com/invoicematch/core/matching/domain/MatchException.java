package com.invoicematch.core.matching.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One machine-readable exception with the values a person needs to recompute
 * it. {@code lineNumber} is null for case-level exceptions such as a suspected
 * duplicate invoice.
 *
 * <p>{@code details} keeps insertion order so the canonical payload is stable.
 */
public record MatchException(MatchExceptionType type, Integer lineNumber, Map<String, Object> details) {

    public MatchException {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (lineNumber != null && lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive when present: " + lineNumber);
        }
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details == null ? Map.of() : details));
    }

    public static MatchException line(MatchExceptionType type, int lineNumber, Map<String, Object> details) {
        return new MatchException(type, lineNumber, details);
    }

    public static MatchException caseLevel(MatchExceptionType type, Map<String, Object> details) {
        return new MatchException(type, null, details);
    }
}
