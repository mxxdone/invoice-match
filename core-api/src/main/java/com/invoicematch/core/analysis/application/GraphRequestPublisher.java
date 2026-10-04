package com.invoicematch.core.analysis.application;

public interface GraphRequestPublisher extends AutoCloseable {
    AnalysisPublishResult publish(String segment,AnalysisPublishCommand command);
    @Override void close();
}
