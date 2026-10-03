package com.invoicematch.core.analysis.application;

/**
 * A machine request conflicts with the authoritative run state, frozen manifest
 * or an already stored result. The {@code code} is fixed and leak-free. Mapped
 * to HTTP 409.
 */
public class AnalysisConflictException extends RuntimeException {

    private final String code;

    public AnalysisConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
