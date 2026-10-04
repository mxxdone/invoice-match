package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Explicit installed Linux wheel acceptance; regular tests require no provider or image build. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="INVOICE_MATCH_GRAPH_LINUX_IMAGE",matches=".+")
class GraphLinuxWorkerIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @DynamicPropertySource static void graphProperties(DynamicPropertyRegistry r) {
        r.add("analysis.graph.enabled",()->true);r.add("analysis.graph.cost-ceiling",()->"1");
        r.add("analysis.graph.lease-duration",()->"75s");
    }
    @Autowired GraphExecutionService graph;
    @Autowired GraphDeliveryService delivery;
    @Autowired GraphReviewService reviews;
    @Autowired com.invoicematch.core.analysis.persistence.GraphStore graphs;
    @LocalServerPort int port;
    private GraphExecutionService.Reserved ready() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"Premium Copy Paper A4"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        return TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
    }
    private GenericContainer<?> worker() {
        return new GenericContainer<>(DockerImageName.parse(System.getenv("INVOICE_MATCH_GRAPH_LINUX_IMAGE")))
            .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(java.nio.file.Path.of("../ai-worker/tests/graph_core_fixture.py").toAbsolutePath()),"/pkg/tests/graph_core_fixture.py")
            .withEnv("GRAPH_FIXTURE_WORKER_TOKEN",WORKER_TOKEN).withCommand("sleep","600")
            .withLabel("invoice-match.test-run",System.getenv().getOrDefault("INVOICE_MATCH_TEST_RUN_ID","unmanaged"))
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withMemory(512L*1024*1024).withPidsLimit(128L));
    }
    private String run(GenericContainer<?> worker,String mode,GraphExecutionService.Reserved r) throws Exception {
        var result=worker.execInContainer("python","/pkg/tests/graph_core_fixture.py",mode,"http://host.docker.internal:"+port,r.id().toString(),r.contextHash());
        assertThat(result.getExitCode()).withFailMessage("Linux graph fixture: %s",result.getStderr()).isZero();
        return result.getStdout();
    }
    private com.invoicematch.core.analysis.infrastructure.RabbitAnalysisRequestPublisher publisher(GenericContainer<?> rabbit,String segment) {
        return new com.invoicematch.core.analysis.infrastructure.RabbitAnalysisRequestPublisher(
            new AnalysisRelayProperties(true,java.time.Duration.ofSeconds(60),java.time.Duration.ZERO,10,java.time.Duration.ofSeconds(5),
                new AnalysisRelayProperties.Rabbit(rabbit.getHost(),rabbit.getMappedPort(5672),"/","graph-test","graph-test-secret",
                    "invoice.graph","invoice.graph."+segment,"ai-review-v2."+segment)),
            segment.equals("start")?"InvoiceGraphRequested":"InvoiceGraphResumeRequested");
    }
    private void publish(GenericContainer<?> rabbit,com.invoicematch.core.analysis.persistence.GraphDeliveryStore.Dispatch d) throws Exception {
        try(var publisher=publisher(rabbit,d.segment().toLowerCase(java.util.Locale.ROOT))) {
            assertThat(publisher.publish(new AnalysisPublishCommand(d.eventId(),d.payload()))).isInstanceOf(AnalysisPublishResult.Published.class);
        }
    }
    private String broker(GenericContainer<?> worker,GenericContainer<?> rabbit,String mode,String segment,int acks,GraphExecutionService.Reserved r) throws Exception {
        var result=worker.execInContainer("python","/pkg/tests/graph_core_fixture.py",mode,"http://host.docker.internal:"+port,r.id().toString(),r.contextHash(),
            rabbit.getMappedPort(5672).toString(),segment,Integer.toString(acks));
        if(mode.equals("kill-resume")) {assertThat(result.getExitCode()).isEqualTo(137);return "killed";}
        assertThat(result.getExitCode()).withFailMessage("Broker graph fixture: %s",result.getStderr()).isZero();return result.getStdout();
    }
    @Test void realConfirmFinalizeLossKillAndExactResumeReclaimRetainBudgetAndAckProof() throws Exception {
        try(var rabbit=new GenericContainer<>(DockerImageName.parse("rabbitmq:4.2-alpine"))
                .withEnv("RABBITMQ_DEFAULT_USER","graph-test").withEnv("RABBITMQ_DEFAULT_PASS","graph-test-secret").withExposedPorts(5672)
                .withLabel("invoice-match.test-run",System.getenv().getOrDefault("INVOICE_MATCH_TEST_RUN_ID","unmanaged"))
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*",1))
                .withStartupTimeout(java.time.Duration.ofMinutes(2));var worker=worker()) {
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
