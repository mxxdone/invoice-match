package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.persistence.AnalysisRecoveryStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shares the bounded publisher; durable recovery dispatch survives ACK and process termination. */
@Service
public class AnalysisRecoveryRelay {
    private final AnalysisRecoveryStore store;
    private final AnalysisRequestPublisher publisher;
    private final AnalysisRelayProperties properties;
    public AnalysisRecoveryRelay(AnalysisRecoveryStore store,AnalysisRequestPublisher publisher,AnalysisRelayProperties properties) {
        this.store=store; this.publisher=publisher; this.properties=properties;
    }
    @Transactional(propagation=Propagation.NEVER)
    public int runOnce() {
        store.recover(); int count=0;
        for (int i=0;i<properties.batchSize();i++) {
            var candidate=store.claim(properties.leaseDuration());
            if(candidate.isEmpty()) break;
            var d=candidate.get(); AnalysisPublishResult result;
            try { result=publisher.publish(new AnalysisPublishCommand(d.eventId(),d.payload(),d.destination().equals("DLQ"))); }
            catch(RuntimeException ignored) { result=new AnalysisPublishResult.Failed(AnalysisPublishError.IO_FAILED); }
            boolean success=result instanceof AnalysisPublishResult.Published;
            String error=success ? null : ((AnalysisPublishResult.Failed)result).error().name();
            if(store.settle(d,success,error,properties.retryDelay()) && success) count++;
        }
        return count;
    }
}
