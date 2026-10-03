package com.invoicematch.core.analysis.application;

import java.util.UUID;

/** No analysis run exists for the requested id. Mapped to HTTP 404. */
public class AnalysisRunNotFoundException extends RuntimeException {

    public AnalysisRunNotFoundException(UUID runId) {
        super("No analysis run exists for id " + runId);
    }
}
