package com.invoicematch.core.analysis.application;

/** Broker confirm happens between claim and finalize transactions. Lost finalize is safely redelivered. */
public class GraphRelay {
    private final GraphDeliveryService delivery;
    private final GraphRequestPublisher publisher;
    public GraphRelay(GraphDeliveryService delivery,GraphRequestPublisher publisher) {this.delivery=delivery;this.publisher=publisher;}
    public void runOnce() {
        for(int i=0;i<10;i++) {
            var claimed=delivery.claimDispatch();if(claimed.isEmpty())return;
            var d=claimed.get();var result=publisher.publish(d.segment(),new AnalysisPublishCommand(d.eventId(),d.payload()));
            boolean confirmed=result instanceof AnalysisPublishResult.Published;
            delivery.settle(d,confirmed);if(!confirmed)return;
        }
    }
}
