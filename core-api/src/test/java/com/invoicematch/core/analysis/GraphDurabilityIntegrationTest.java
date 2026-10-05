package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;

import com.invoicematch.core.analysis.application.GraphExecutionService;
import com.invoicematch.core.analysis.persistence.GraphDeliveryStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * P4-07 durability acceptance: only the human-required case parks, a fresh
 * worker restores after every worker process is gone, and independent resume
 * deliveries complete in reverse order without crossing threads or budgets.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="INVOICE_MATCH_GRAPH_LINUX_IMAGE",matches=".+")
class GraphDurabilityIntegrationTest extends AbstractGraphWorkerIntegrationTest {
    @DynamicPropertySource static void durabilityProperties(DynamicPropertyRegistry r) {
        r.add("analysis.graph.lease-duration",()->"75s");
    }
    private final Map<UUID,GraphDeliveryStore.Dispatch> held=new HashMap<>();
    private String status(GraphExecutionService.Reserved r) {
        return jdbc.queryForObject("select status from graph_run where id=?",String.class,r.id());
    }
    /** Claims the READY dispatch of exactly one run/segment; other claims are held for later assertions. */
    private GraphDeliveryStore.Dispatch take(UUID runId,String segment) {
        var cached=held.remove(runId);
        if(cached!=null) {assertThat(cached.segment()).isEqualTo(segment);return cached;}
        for(int attempt=0;attempt<24;attempt++) {
            var next=delivery.claimDispatch();
            if(next.isEmpty())continue;
            var dispatch=next.get();
            if(dispatch.runId().equals(runId)&&dispatch.segment().equals(segment))return dispatch;
            assertThat(held.put(dispatch.runId(),dispatch)).isNull();
        }
        throw new AssertionError(segment+" dispatch for "+runId+" not found; held="+held.keySet());
    }
    @Test void onlyHumanRequiredCaseWaitsAndReverseOrderResumeSurvivesAllWorkersStopping() throws Exception {
        try(var rabbit=rabbit();var first=worker()) {
            rabbit.start();first.start();
            var humanA=ready();
            var startA=take(humanA.id(),"START");publish(rabbit,startA);assertThat(delivery.settle(startA,true)).isTrue();
            assertThat(broker(first,rabbit,"broker-start","start",1,humanA)).contains("\"modelCalls\": 1");
            assertThat(status(humanA)).isEqualTo("WAITING_HUMAN");
            // Two independent peer cases run to completion while exactly one case parks.
            var normalB=ready();var normalC=ready();
            assertThat(run(first,"normal",normalB)).contains("modelCalls");
            assertThat(run(first,"normal",normalC)).contains("modelCalls");
            assertThat(status(normalB)).isEqualTo("COMPLETED");
            assertThat(status(normalC)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("select count(*) from graph_run where status='WAITING_HUMAN'",Integer.class)).isEqualTo(1);
            // Every worker process is gone before any restore or resume is attempted.
            first.stop();
            var humanD=ready();
            try(var second=worker()) {
                second.start();
                var startD=take(humanD.id(),"START");publish(rabbit,startD);assertThat(delivery.settle(startD,true)).isTrue();
                assertThat(broker(second,rabbit,"broker-start","start",1,humanD)).contains("\"modelCalls\": 1");
                assertThat(status(humanD)).isEqualTo("WAITING_HUMAN");
                // Both threads were restored from PostgreSQL only; confirm each exact interrupt.
                var reviewA=confirmHuman(humanA);UUID reviewD=confirmHuman(humanD);
                var dispatchD=take(humanD.id(),"RESUME");var dispatchA=take(humanA.id(),"RESUME");
                // Reverse creation order: the newer thread's resume is published and consumed first.
                publish(rabbit,dispatchD);assertThat(delivery.settle(dispatchD,true)).isTrue();
                publish(rabbit,dispatchA);assertThat(delivery.settle(dispatchA,true)).isTrue();
                assertThat(broker(second,rabbit,"broker-resume","resume",1,humanD)).contains("\"modelCalls\": 1");
                assertThat(status(humanD)).isEqualTo("COMPLETED");
                assertThat(status(humanA)).isNotEqualTo("COMPLETED");
                assertThat(jdbc.queryForObject("select count(*) from graph_proposal where run_id=?",Integer.class,humanA.id())).isZero();
                // A was parked by the first container and restored by the second: no saved model work repeats.
                assertThat(broker(second,rabbit,"broker-resume","resume",1,humanA)).contains("\"acks\": 1","\"modelCalls\": 0");
                assertThat(status(humanA)).isEqualTo("COMPLETED");
                // The same resume delivery replays without a second model call or a new consumption.
                int consumptions=count("graph_resume_consumption");
                publish(rabbit,dispatchD);
                assertThat(broker(second,rabbit,"broker-resume","resume",1,humanD)).contains("\"modelCalls\": 1");
                assertThat(count("graph_resume_consumption")).isEqualTo(consumptions);
                // Each thread keeps its own immutable human review and per-run budget.
                assertThat(jdbc.queryForObject("select payload->'humanReview'->>'reviewId' from graph_proposal where run_id=?",String.class,humanA.id()))
                    .isEqualTo(reviewA.toString());
                assertThat(jdbc.queryForObject("select payload->'humanReview'->>'reviewId' from graph_proposal where run_id=?",String.class,humanD.id()))
                    .isEqualTo(reviewD.toString());
                for(var r:List.of(humanA,humanD))
                    assertThat(jdbc.queryForMap("select start_attempts,resume_attempts,reserved_calls from graph_run where id=?",r.id()))
                        .containsEntry("start_attempts",1).containsEntry("resume_attempts",1).containsEntry("reserved_calls",1);
                assertThat(count("receipt_allocation")).isZero();assertThat(count("payment_request")).isZero();
                // Completion response loss: the real Core already stored COMPLETED, so the retry converges once.
                var lost=ready();
                assertThat(run(second,"complete-loss",lost)).contains("\"modelCalls\": 2");
                assertThat(status(lost)).isEqualTo("COMPLETED");
                assertThat(run(second,"complete-loss",lost)).contains("\"modelCalls\": 2");
                assertThat(jdbc.queryForObject("select count(*) from graph_proposal where run_id=?",Integer.class,lost.id())).isEqualTo(1);
            }
        }
    }
}
