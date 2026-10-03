package com.invoicematch.core.analysis.application;

/**
 * A machine request failed input validation. The {@code code} is a fixed,
 * leak-free classification; the message never carries parser content, internal
 * paths or credentials. Mapped to HTTP 400.
 */
public class AnalysisValidationException extends RuntimeException {

    private final String code;

    public AnalysisValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
