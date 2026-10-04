package com.invoicematch.core.analysis.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.invoicematch.core.analysis.application.AnalysisRelayProperties;
import org.junit.jupiter.api.Test;

class GraphPublisherTopologyTest {
    private AnalysisRelayProperties properties(String exchange,String queue,String key) {
        return new AnalysisRelayProperties(false,null,null,1,null,
            new AnalysisRelayProperties.Rabbit("localhost",5672,"/","","",exchange,queue,key));
    }
    @Test void graphTypesCannotPublishIntoLegacyOrTheOtherSegmentTopology() {
        for(String type:java.util.List.of("InvoiceGraphRequested","InvoiceGraphResumeRequested")) {
            assertThatThrownBy(()->new RabbitAnalysisRequestPublisher(properties("invoice.proposal","invoice.proposal.requests","ai-review-v1"),type))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new RabbitAnalysisRequestPublisher(properties("invoice.analysis","invoice.analysis.requests","document-parser-v1"),type))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->new RabbitAnalysisRequestPublisher(properties("invoice.graph","invoice.graph.start","ai-review-v2.start"),"InvoiceGraphResumeRequested"))
            .isInstanceOf(IllegalArgumentException.class);
        try(var start=new RabbitAnalysisRequestPublisher(properties("invoice.graph","invoice.graph.start","ai-review-v2.start"),"InvoiceGraphRequested");
            var resume=new RabbitAnalysisRequestPublisher(properties("invoice.graph","invoice.graph.resume","ai-review-v2.resume"),"InvoiceGraphResumeRequested")) {
            assertThat(start).isNotNull();assertThat(resume).isNotNull();
        }
    }
}
