package com.invoicematch.core.analysis.infrastructure;

import com.invoicematch.core.analysis.application.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@ConditionalOnProperty(prefix="analysis.graph.relay",name="enabled",havingValue="true")
public class GraphRelayConfiguration {
    @Bean(destroyMethod="close") GraphRequestPublisher graphPublisher(GraphProperties graph,
            @Value("${analysis.graph.relay.rabbit.host:}") String host,@Value("${analysis.graph.relay.rabbit.port:5672}") int port,
            @Value("${analysis.graph.relay.rabbit.vhost:/}") String vhost,@Value("${analysis.graph.relay.rabbit.username:}") String username,
            @Value("${analysis.graph.relay.rabbit.password:}") String password) {
        if(!graph.enabled())throw new IllegalArgumentException("Graph relay requires graph enabled");
        var start=new RabbitAnalysisRequestPublisher(properties(host,port,vhost,username,password,"start"),"InvoiceGraphRequested");
        var resume=new RabbitAnalysisRequestPublisher(properties(host,port,vhost,username,password,"resume"),"InvoiceGraphResumeRequested");
        return new GraphRequestPublisher() {
            public AnalysisPublishResult publish(String segment,AnalysisPublishCommand command) {
                return switch(segment) {case "START"->start.publish(command);case "RESUME"->resume.publish(command);default->throw new IllegalArgumentException("Unsupported graph segment");};
            }
            public void close() {start.close();resume.close();}
        };
    }
    private static AnalysisRelayProperties properties(String host,int port,String vhost,String username,String password,String segment) {
        return new AnalysisRelayProperties(true,Duration.ofSeconds(60),Duration.ofSeconds(30),10,Duration.ofSeconds(5),
            new AnalysisRelayProperties.Rabbit(host,port,vhost,username,password,"invoice.graph","invoice.graph."+segment,"ai-review-v2."+segment));
    }
    @Bean GraphRelay graphRelay(GraphDeliveryService delivery,GraphRequestPublisher publisher) {return new GraphRelay(delivery,publisher);}
    @Bean GraphTick graphTick(GraphRelay relay) {return new GraphTick(relay);}
    static final class GraphTick {
        private final GraphRelay relay;private final AtomicBoolean running=new AtomicBoolean();
        GraphTick(GraphRelay relay) {this.relay=relay;}
        @Scheduled(fixedDelayString="${analysis.graph.relay.interval:5s}") public void tick() {
            if(!running.compareAndSet(false,true))return;try{relay.runOnce();}finally{running.set(false);}
        }
    }
}
