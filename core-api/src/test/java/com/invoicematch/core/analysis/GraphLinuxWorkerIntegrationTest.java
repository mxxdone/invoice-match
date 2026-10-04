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
    }
    @Autowired GraphExecutionService graph;
    @LocalServerPort int port;
    private GraphExecutionService.Reserved ready() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"Premium Copy Paper A4"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        return TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
    }
    private GenericContainer<?> worker() {
        return new GenericContainer<>(DockerImageName.parse(System.getenv("INVOICE_MATCH_GRAPH_LINUX_IMAGE")))
            .withEnv("GRAPH_FIXTURE_WORKER_TOKEN",WORKER_TOKEN).withCommand("sleep","600")
            .withLabel("invoice-match.test-run",System.getenv().getOrDefault("INVOICE_MATCH_TEST_RUN_ID","unmanaged"))
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withMemory(512L*1024*1024).withPidsLimit(128L));
    }
    private String run(GenericContainer<?> worker,String mode,GraphExecutionService.Reserved r) throws Exception {
        var result=worker.execInContainer("python","/pkg/tests/graph_core_fixture.py",mode,"http://host.docker.internal:"+port,r.id().toString(),r.contextHash());
        assertThat(result.getExitCode()).withFailMessage("Linux graph fixture: %s",result.getStderr()).isZero();
        return result.getStdout();
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
