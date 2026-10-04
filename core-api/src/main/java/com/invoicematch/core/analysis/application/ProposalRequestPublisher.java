package com.invoicematch.core.analysis.application;

public interface ProposalRequestPublisher extends AutoCloseable {
    AnalysisPublishResult publish(AnalysisPublishCommand command);
    @Override void close();
}
