package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;

import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.analysis.persistence.GraphDeliveryStore;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.approval.application.ApprovalApplicationService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared harness for the explicit installed Linux wheel acceptance: a real
 * Core, real PostgreSQL, a real RabbitMQ when a broker path is exercised, and
 * the real pinned LangGraph inside the image. Model replies stay in a
 * test-only fixture; Core, broker, pgvector and the SDK are never mocked.
 */
abstract class AbstractGraphWorkerIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @DynamicPropertySource static void graphWorkerProperties(DynamicPropertyRegistry r) {
        r.add("analysis.graph.enabled",()->true);r.add("analysis.graph.cost-ceiling",()->"1");
    }
    @Autowired GraphExecutionService graph;
    @Autowired GraphDeliveryService delivery;
    @Autowired GraphReviewService reviews;
    @Autowired GraphStore graphs;
    @Autowired ApprovalApplicationService approvals;
    @LocalServerPort protected int port;

    /** One reserved graph run whose frozen parser and match inputs are current. */
    protected GraphExecutionService.Reserved ready() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"Premium Copy Paper A4"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        return TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
    }
    protected GenericContainer<?> rabbit() {
        return new GenericContainer<>(DockerImageName.parse("rabbitmq:4.2-alpine"))
            .withEnv("RABBITMQ_DEFAULT_USER","graph-test").withEnv("RABBITMQ_DEFAULT_PASS","graph-test-secret").withExposedPorts(5672)
            .withLabel("invoice-match.test-run",System.getenv().getOrDefault("INVOICE_MATCH_TEST_RUN_ID","unmanaged"))
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*",1))
            .withStartupTimeout(Duration.ofMinutes(2));
    }
    protected GenericContainer<?> worker() {
        return new GenericContainer<>(DockerImageName.parse(System.getenv("INVOICE_MATCH_GRAPH_LINUX_IMAGE")))
            .withCopyFileToContainer(MountableFile.forHostPath(java.nio.file.Path.of("../ai-worker/tests/graph_core_fixture.py").toAbsolutePath()),"/pkg/tests/graph_core_fixture.py")
            .withEnv("GRAPH_FIXTURE_WORKER_TOKEN",WORKER_TOKEN).withCommand("sleep","600")
            .withLabel("invoice-match.test-run",System.getenv().getOrDefault("INVOICE_MATCH_TEST_RUN_ID","unmanaged"))
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withMemory(512L*1024*1024).withPidsLimit(128L));
    }
    protected String run(GenericContainer<?> worker,String mode,GraphExecutionService.Reserved r) throws Exception {
        var result=worker.execInContainer("python","/pkg/tests/graph_core_fixture.py",mode,"http://host.docker.internal:"+port,r.id().toString(),r.contextHash());
        assertThat(result.getExitCode()).withFailMessage("Linux graph fixture: %s",result.getStderr()).isZero();
        return result.getStdout();
    }
    protected com.invoicematch.core.analysis.infrastructure.RabbitAnalysisRequestPublisher publisher(GenericContainer<?> rabbit,String segment) {
        return new com.invoicematch.core.analysis.infrastructure.RabbitAnalysisRequestPublisher(
            new AnalysisRelayProperties(true,Duration.ofSeconds(60),Duration.ZERO,10,Duration.ofSeconds(5),
                new AnalysisRelayProperties.Rabbit(rabbit.getHost(),rabbit.getMappedPort(5672),"/","graph-test","graph-test-secret",
                    "invoice.graph","invoice.graph."+segment,"ai-review-v2."+segment)),
            segment.equals("start")?"InvoiceGraphRequested":"InvoiceGraphResumeRequested");
    }
    protected void publish(GenericContainer<?> rabbit,GraphDeliveryStore.Dispatch d) throws Exception {
        try(var publisher=publisher(rabbit,d.segment().toLowerCase(java.util.Locale.ROOT))) {
            assertThat(publisher.publish(new AnalysisPublishCommand(d.eventId(),d.payload()))).isInstanceOf(AnalysisPublishResult.Published.class);
        }
    }
    protected String broker(GenericContainer<?> worker,GenericContainer<?> rabbit,String mode,String segment,int acks,GraphExecutionService.Reserved r) throws Exception {
        var result=worker.execInContainer("python","/pkg/tests/graph_core_fixture.py",mode,"http://host.docker.internal:"+port,r.id().toString(),r.contextHash(),
            rabbit.getMappedPort(5672).toString(),segment,Integer.toString(acks));
        if(mode.equals("kill-resume")) {assertThat(result.getExitCode()).isEqualTo(137);return "killed";}
        assertThat(result.getExitCode()).withFailMessage("Broker graph fixture: %s",result.getStderr()).isZero();return result.getStdout();
    }
    /** Records the OPERATOR confirmation of the exact pending interrupt and returns its immutable review id. */
    protected UUID confirmHuman(GraphExecutionService.Reserved r) {
        var wait=graphs.waiting(r.id()).orElseThrow();var saved=graphs.stages(r.id());
        var confirmation=json.createObjectNode().put("documentDecision","CONFIRMED")
            .put("documentStageRef",saved.stream().filter(s->s.stage().equals("document")).findFirst().orElseThrow().id().toString())
            .put("mappingStageRef",saved.stream().filter(s->s.stage().equals("mapping")).findFirst().orElseThrow().id().toString());
        confirmation.putArray("itemDecisions");
        UUID caseId=jdbc.queryForObject("select invoice_case_id from graph_run where id=?",UUID.class,r.id());
        return TestActors.call("operator","OPERATOR",()->reviews.confirm(caseId,r.id(),new GraphReviewService.Command("confirmed-"+r.id(),caseVersion(caseId),
            wait.interruptId(),wait.checkpointHash(),1,confirmation,"원문을 확인했습니다."))).body().reviewId();
    }
}
