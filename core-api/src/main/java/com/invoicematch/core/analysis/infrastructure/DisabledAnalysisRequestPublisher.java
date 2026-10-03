package com.invoicematch.core.analysis.infrastructure;

import com.invoicematch.core.analysis.application.AnalysisPublishCommand;
import com.invoicematch.core.analysis.application.AnalysisPublishError;
import com.invoicematch.core.analysis.application.AnalysisPublishResult;
import com.invoicematch.core.analysis.application.AnalysisRequestPublisher;

/**
 * Fail-closed publisher used while {@code analysis.relay.enabled=false}. It never
 * creates an SDK connection, so even a direct relay invocation can only return a
 * {@code RELAY_DISABLED} failure and leave the request READY. In normal
 * operation the scheduler is absent while disabled and this adapter is never
 * reached.
 */
public class DisabledAnalysisRequestPublisher implements AnalysisRequestPublisher {

    @Override
    public AnalysisPublishResult publish(AnalysisPublishCommand command) {
        return new AnalysisPublishResult.Failed(AnalysisPublishError.RELAY_DISABLED);
    }
}
