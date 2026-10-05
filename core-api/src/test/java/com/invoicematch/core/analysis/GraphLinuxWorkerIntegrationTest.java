package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;

import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Explicit installed Linux wheel acceptance; regular tests require no provider or image build. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="INVOICE_MATCH_GRAPH_LINUX_IMAGE",matches=".+")
class GraphLinuxWorkerIntegrationTest extends AbstractGraphWorkerIntegrationTest {
    @DynamicPropertySource static void graphProperties(DynamicPropertyRegistry r) {
        r.add("analysis.graph.lease-duration",()->"75s");
    }
    @Test void realConfirmFinalizeLossKillAndExactResumeReclaimRetainBudgetAndAckProof() throws Exception {
        try(var rabbit=rabbit();var worker=worker()) {
            rabbit.start();worker.start();var r=ready();var first=delivery.claimDispatch().orElseThrow();
            publish(rabbit,first); // Simulate process death after real confirm, before finalize.
            jdbc.update("update graph_dispatch set lease_until=clock_timestamp()-interval '1 second' where id=?",first.id());
            var reclaimed=delivery.claimDispatch().orElseThrow();assertThat(reclaimed.token()).isNotEqualTo(first.token());
            assertThat(delivery.settle(first,true)).isFalse();publish(rabbit,reclaimed);assertThat(delivery.settle(reclaimed,true)).isTrue();
            assertThat(broker(worker,rabbit,"broker-start","start",2,r)).contains("\"acks\": 2","\"modelCalls\": 1");
            assertThat(jdbc.queryForObject("select status from graph_run where id=?",String.class,r.id())).isEqualTo("WAITING_HUMAN");
            var wait=graphs.waiting(r.id()).orElseThrow();var saved=graphs.stages(r.id());
            var confirmation=json.createObjectNode().put("documentDecision","CONFIRMED")
                .put("documentStageRef",saved.stream().filter(s->s.stage().equals("document")).findFirst().orElseThrow().id().toString())
                .put("mappingStageRef",saved.stream().filter(s->s.stage().equals("mapping")).findFirst().orElseThrow().id().toString());confirmation.putArray("itemDecisions");
            UUID caseId=jdbc.queryForObject("select invoice_case_id from graph_run where id=?",UUID.class,r.id());
            var review=TestActors.call("operator","OPERATOR",()->reviews.confirm(caseId,r.id(),new GraphReviewService.Command("confirmed",caseVersion(caseId),
                wait.interruptId(),wait.checkpointHash(),1,confirmation,"원문을 확인했습니다."))).body();
            var resume=delivery.claimDispatch().orElseThrow();assertThat(resume.segment()).isEqualTo("RESUME");publish(rabbit,resume);delivery.settle(resume,true);
            broker(worker,rabbit,"kill-resume","resume",1,r);
            UUID oldToken=jdbc.queryForObject("select execution_token from graph_run where id=?",UUID.class,r.id());
            assertThat(broker(worker,rabbit,"broker-busy","resume",1,r)).contains("\"acks\": 1");
            assertThat(jdbc.queryForObject("select count(*) from graph_dispatch where dedup_key like 'defer:%'",Integer.class)).isEqualTo(1);
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(90).toNanos();
            while(Boolean.TRUE.equals(jdbc.queryForObject("select lease_until>clock_timestamp() from graph_run where id=?",Boolean.class,r.id()))) {
                assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(100);
            }
            var retry=delivery.claimDispatch().orElseThrow();publish(rabbit,retry);delivery.settle(retry,true);publish(rabbit,retry);
            assertThat(broker(worker,rabbit,"broker-resume","resume",2,r)).contains("\"acks\": 2","\"modelCalls\": 1");
            assertThat(jdbc.queryForMap("select status,start_attempts,resume_attempts,reserved_calls from graph_run where id=?",r.id()))
                .withFailMessage("Graph recovery: %s; failures: %s; stages: %s",jdbc.queryForMap("select status,error_code from graph_run where id=?",r.id()),
                    jdbc.queryForList("select error_code,run_status from graph_failure where run_id=?",r.id()),graphs.stages(r.id()).stream().map(s->s.stage()).toList())
                .containsEntry("status","COMPLETED").containsEntry("start_attempts",1).containsEntry("resume_attempts",2).containsEntry("reserved_calls",1);
            assertThat(jdbc.queryForObject("select payload->'humanReview'->>'reviewId' from graph_proposal where run_id=?",String.class,r.id())).isEqualTo(review.reviewId().toString());
            assertThatThrownBy(()->graph.heartbeat(r.id(),r.contextHash(),oldToken)).isInstanceOf(AnalysisConflictException.class);
            assertThat(count("graph_resume_consumption")).isEqualTo(1);assertThat(count("proposal_run")).isZero();assertThat(count("receipt_allocation")).isZero();
            // The real SDK completion of a human resume is a freezable, approvable proof.
            UUID completedCaseId=jdbc.queryForObject("select invoice_case_id from graph_run where id=?",UUID.class,r.id());
            String completedHash=jdbc.queryForObject("select payload_hash from graph_proposal where run_id=?",String.class,r.id());
            TestActors.run("approver","APPROVER",()->reviewService.freezeSnapshot(
                new com.invoicematch.core.review.application.FreezeReviewSnapshotCommand(completedCaseId,UUID.randomUUID().toString(),caseVersion(completedCaseId),r.id(),completedHash)));
            var completedSnapshot=reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(completedCaseId).orElseThrow();
            TestActors.run("approver","APPROVER",()->approvals.approve(new com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand(
                completedCaseId,UUID.randomUUID().toString(),caseVersion(completedCaseId),completedSnapshot.id(),completedSnapshot.payloadHash())));
            assertThat(count("payment_request")).isEqualTo(1);
            assertThat(count("receipt_allocation")).isEqualTo(1);
        }
    }
    @Test void failedConfirmRetainsDurableDeliveryForRetry() {
        var r=ready();var dispatch=delivery.claimDispatch().orElseThrow();
        var p=new AnalysisRelayProperties(true,java.time.Duration.ofSeconds(60),java.time.Duration.ZERO,1,java.time.Duration.ofSeconds(5),
            new AnalysisRelayProperties.Rabbit("127.0.0.1",1,"/","graph-test","graph-test-secret","invoice.graph","invoice.graph.start","ai-review-v2.start"));
        try(var publisher=new com.invoicematch.core.analysis.infrastructure.RabbitAnalysisRequestPublisher(p,"InvoiceGraphRequested")) {
            assertThat(publisher.publish(new AnalysisPublishCommand(dispatch.eventId(),dispatch.payload()))).isInstanceOf(AnalysisPublishResult.Failed.class);
            assertThat(delivery.settle(dispatch,false)).isTrue();
        }
        assertThat(jdbc.queryForObject("select status from graph_dispatch where id=?",String.class,dispatch.id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select start_attempts from graph_run where id=?",Integer.class,r.id())).isZero();
    }
    @Test void actualCoreRestoresInstalledLinuxGraphAndReleasesConsumerBetweenCases() throws Exception {
        var waiting=ready();var normal=ready();
        try(var worker=worker()) {
            worker.start();assertThat(run(worker,"break-wait",waiting)).contains("\"modelCalls\": 1");
            assertThat(jdbc.queryForObject("select status from graph_run where id=?",String.class,waiting.id())).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("select count(*) from graph_interrupt where run_id=?",Integer.class,waiting.id())).isZero();
            int checkpoints=jdbc.queryForObject("select checkpoint_count from graph_run where id=?",Integer.class,waiting.id());
            assertThat(run(worker,"restore",waiting)).contains("\"modelCalls\": 1");
            var row=jdbc.queryForMap("select status,execution_token,lease_until,reserved_calls,checkpoint_count from graph_run where id=?",waiting.id());
            assertThat(row.get("status")).isEqualTo("WAITING_HUMAN");assertThat(row.get("execution_token")).isNull();assertThat(row.get("lease_until")).isNull();
            assertThat(row.get("reserved_calls")).isEqualTo(1);assertThat(row.get("checkpoint_count")).isEqualTo(checkpoints);
            assertThat(run(worker,"normal",normal)).contains("modelCalls");
            assertThat(jdbc.queryForObject("select status from graph_run where id=?",String.class,normal.id())).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("select payload->>'schemaVersion' from graph_proposal where run_id=?",String.class,normal.id())).isEqualTo("advisory-proposal-v2");
            assertThat(jdbc.queryForObject("select count(*) from graph_pending_write where task_path<>''",Integer.class)).isPositive();
            assertThat(count("proposal_run")).isZero();assertThat(count("proposal")).isZero();assertThat(count("receipt_allocation")).isZero();assertThat(count("payment_request")).isZero();
        }
    }
}
