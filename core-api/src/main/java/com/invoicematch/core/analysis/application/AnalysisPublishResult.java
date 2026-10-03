package com.invoicematch.core.analysis.application;

import java.util.Objects;

/**
 * SDK-free outcome of one publish attempt. {@code Published} is only returned
 * after a publisher confirm ACK with no mandatory return; every other outcome
 * carries a fixed {@link AnalysisPublishError} classification.
 */
public sealed interface AnalysisPublishResult {

    record Published() implements AnalysisPublishResult {
    }

    record Failed(AnalysisPublishError error) implements AnalysisPublishResult {

        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }
}
