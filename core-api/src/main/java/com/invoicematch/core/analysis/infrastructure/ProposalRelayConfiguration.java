package com.invoicematch.core.analysis.infrastructure;

import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.analysis.persistence.ProposalRecoveryStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@ConditionalOnProperty(prefix="analysis.ai.relay",name="enabled",havingValue="true")
public class ProposalRelayConfiguration {
    @Bean(destroyMethod="close") ProposalRequestPublisher proposalPublisher(ProposalProperties ai,
            @Value("${analysis.ai.relay.rabbit.host:}") String host,@Value("${analysis.ai.relay.rabbit.port:5672}") int port,
            @Value("${analysis.ai.relay.rabbit.vhost:/}") String vhost,@Value("${analysis.ai.relay.rabbit.username:}") String username,
            @Value("${analysis.ai.relay.rabbit.password:}") String password) {
        if(!ai.enabled()) throw new IllegalArgumentException("AI relay requires AI enabled");
        var p=new AnalysisRelayProperties(true,Duration.ofSeconds(60),Duration.ofSeconds(30),5,Duration.ofSeconds(5),
            new AnalysisRelayProperties.Rabbit(host,port,vhost,username,password,"invoice.proposal","invoice.proposal.requests","ai-review-v1"));
        var delegate=new RabbitAnalysisRequestPublisher(p,"InvoiceProposalRequested");
        return new ProposalRequestPublisher() {
            public AnalysisPublishResult publish(AnalysisPublishCommand command) {return delegate.publish(command);}
            public void close() {delegate.close();}
        };
    }
    @Bean ProposalRelay proposalRelay(ProposalRecoveryStore store,ProposalRequestPublisher publisher) {return new ProposalRelay(store,publisher);}
    @Bean ProposalRelayTick proposalRelayTick(ProposalRelay relay) {return new ProposalRelayTick(relay);}
    static final class ProposalRelayTick {
        final ProposalRelay relay;final AtomicBoolean running=new AtomicBoolean();
        ProposalRelayTick(ProposalRelay relay) {this.relay=relay;}
        @Scheduled(fixedDelayString="${analysis.ai.relay.interval:5s}") public void tick() {
            if(!running.compareAndSet(false,true)) return;try {relay.runOnce();} finally {running.set(false);}
        }
    }
}
