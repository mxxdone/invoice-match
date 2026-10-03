package com.invoicematch.core.analysis.application;

/**
 * Application port for publishing an analysis-request message. The interface
 * and its request/result types are deliberately SDK-free: the RabbitMQ client
 * lives only in the infrastructure adapter, and this port is never imported by
 * the domain or persistence layers.
 */
public interface AnalysisRequestPublisher {

    /**
     * Attempts exactly one publish. A {@link AnalysisPublishResult.Published}
     * result is only returned after publisher confirm ACK and the absence of a
     * mandatory return. Implementations must bound the whole attempt (connect,
     * declare, publish, confirm) and must not throw for an ordinary broker
     * failure — they return a classified failure instead.
     */
    AnalysisPublishResult publish(AnalysisPublishCommand command);
}
