package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.persistence.ProposalRecoveryStore;
import java.time.Duration;

/** Publisher I/O happens after the claim transaction and before fenced settlement. */
public class ProposalRelay {
    final ProposalRecoveryStore store;final ProposalRequestPublisher publisher;
    public ProposalRelay(ProposalRecoveryStore store,ProposalRequestPublisher publisher) {this.store=store;this.publisher=publisher;}
    public void runOnce() {
        for(boolean initial:new boolean[]{true,false}) for(int n=0;n<5;n++) {
            var dispatch=store.claim(initial,Duration.ofSeconds(60));if(dispatch.isEmpty()) break;
            var d=dispatch.get();var result=publisher.publish(new AnalysisPublishCommand(d.eventId(),d.payload()));
            store.settle(d,result instanceof AnalysisPublishResult.Published,Duration.ofSeconds(30));
            if(result instanceof AnalysisPublishResult.Failed) break;
        }
    }
}
