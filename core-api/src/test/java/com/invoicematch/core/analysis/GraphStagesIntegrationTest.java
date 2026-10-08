package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class GraphStagesIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @DynamicPropertySource static void graphProperties(DynamicPropertyRegistry r) {
        r.add("analysis.ai.enabled",()->false);r.add("analysis.graph.enabled",()->true);r.add("analysis.graph.cost-ceiling",()->"1");
    }
    @Autowired GraphExecutionService graph;
    @Autowired GraphStageService stages;
    protected record Fixture(RunFixture parser,GraphExecutionService.Reserved graph,GraphExecutionService.Claim claim) {}
    protected Fixture ready() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"Premium Copy Paper A4"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        var r=TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
        return new Fixture(f,r,graph.claim(r.id(),r.contextHash()));
    }
    private ObjectNode plan() {
        var plan=json.createObjectNode().put("schemaVersion","ai-execution-plan-v1").put("model","fixture").put("providerFingerprint","1".repeat(64))
            .put("inputPricePerMillion",1).put("outputPricePerMillion",2).put("currency","USD").put("costCeiling",1)
            .put("tokenParameter","max_completion_tokens").put("promptVersion","invoice-advisory-1");plan.putNull("embedding");return plan;
    }
    @Test
    void graphFrozenQueriesUseTheirOwnLedgerWhileLegacyExecutionIsDisabled() {
        var f = ready();
        var r = f.graph();
        var token = f.claim().token();
        var tool = new ProposalToolService.Request(UUID.randomUUID(), "get_purchase_order", "", 3);
        var policy = new PolicySearchService.Request(UUID.randomUUID(), "규칙", "LEXICAL", null, null, null, 3);

        var toolReply = stages.tool(r.id(), r.contextHash(), token, tool);
        var policyReply = stages.policy(r.id(), r.contextHash(), token, policy);
        assertThat(stages.tool(r.id(), r.contextHash(), token, tool)).isEqualTo(toolReply);
        assertThat(stages.policy(r.id(), r.contextHash(), token, policy)).isEqualTo(policyReply);
        assertThat(toolReply.path("result").path("purchaseOrderId").asText()).isEqualTo("PO-1001");
        assertThat(policyReply.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(policyReply.path("contextHash").asText()).isEqualTo(r.contextHash());
        assertThat(jdbc.queryForObject("select tool_calls from graph_run where id=?", Integer.class, r.id()))
                .isEqualTo(2);

        assertThatThrownBy(() -> stages.tool(r.id(), r.contextHash(), token,
                new ProposalToolService.Request(tool.requestId(), "get_receipts", "", 3)))
                .isInstanceOf(AnalysisConflictException.class);
        assertThatThrownBy(() -> stages.policy(r.id(), r.contextHash(), token,
                new PolicySearchService.Request(policy.requestId(), "다른 규칙", "LEXICAL", null, null, null, 3)))
                .isInstanceOf(AnalysisConflictException.class);
        assertThatThrownBy(() -> stages.policy(r.id(), r.contextHash(), UUID.randomUUID(), policy))
                .isInstanceOf(AnalysisConflictException.class);
        assertThat(jdbc.queryForObject("select tool_calls from graph_run where id=?", Integer.class, r.id()))
                .isEqualTo(2);
        assertThat(count("proposal_run")).isZero();
        assertThat(count("proposal_step")).isZero();
    }

    @Test void validatedStagesAndReservationsAreFencedImmutableAndCannotResetBudgets() {
        var f=ready();var r=f.graph();var token=f.claim().token();
        var saved=stages.save(r.id(),r.contextHash(),token,"execution",plan());
        assertThat(stages.save(r.id(),r.contextHash(),token,"execution",plan()).ref()).isEqualTo(saved.ref());
        UUID call=UUID.randomUUID();assertThat(stages.reserve(r.id(),r.contextHash(),token,call,1000)).isTrue();
        assertThat(stages.reserve(r.id(),r.contextHash(),token,call,1000)).isFalse();
        assertThat(jdbc.queryForObject("select reserved_calls from graph_run where id=?",Integer.class,r.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reserved_cost from graph_run where id=?",java.math.BigDecimal.class,r.id())).isEqualByComparingTo("0.002");
        assertThatThrownBy(()->stages.save(r.id(),r.contextHash(),UUID.randomUUID(),"execution",plan())).isInstanceOf(AnalysisConflictException.class);
        var bad=plan().put("model","different");assertThatThrownBy(()->stages.save(r.id(),r.contextHash(),token,"execution",bad)).isInstanceOf(AnalysisConflictException.class);
        assertDatabaseRejects("P0001",()->jdbc.update("update graph_stage set payload='{}' where run_id=?",r.id()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_call_reservation where run_id=?",r.id()));
        assertDatabaseRejects("23514",()->jdbc.update("update graph_run set reserved_cost=0 where id=?",r.id()));
        assertThat(stages.read(r.id(),r.contextHash(),token)).hasSize(1);
        assertThat(count("proposal_run")).isZero();assertThat(count("proposal_step")).isZero();
    }
    @Test void coreValidatesSourcesBudgetAndToolScopeAndDoesNotCompleteHumanRequiredResult() {
        var f=ready();var r=f.graph();var t=f.claim().token();stages.save(r.id(),r.contextHash(),t,"execution",plan());stages.reserve(r.id(),r.contextHash(),t,UUID.randomUUID(),1000);
        var doc=json.createObjectNode().put("schemaVersion","invoice-extraction-v1").put("promptVersion","invoice-advisory-1");
        doc.putArray("calls").addObject().put("model","fixture").put("inputTokens",100).put("outputTokens",100).put("latencyMs",1);
        var result=doc.putObject("result");result.putArray("fields");result.putArray("lines");result.putArray("warnings").add("EMPTY_DOCUMENT");
        stages.save(r.id(),r.contextHash(),t,"document",doc);
        var mapping=json.createObjectNode().put("schemaVersion","item-mapping-v1").put("promptVersion","invoice-advisory-1");mapping.putArray("calls");mapping.putObject("result").putArray("lines");
        stages.save(r.id(),r.contextHash(),t,"mapping",mapping);
        assertThatThrownBy(()->stages.complete(r.id(),r.contextHash(),t)).isInstanceOf(AnalysisConflictException.class)
            .extracting(e->((AnalysisConflictException)e).code()).isEqualTo("GRAPH_HUMAN_REQUIRED");
        assertThat(count("graph_proposal")).isZero();
        var tool=new ProposalToolService.Request(UUID.randomUUID(),"get_prior_invoice_cases","",10);
        assertThat(stages.tool(r.id(),r.contextHash(),t,tool)).isEqualTo(stages.tool(r.id(),r.contextHash(),t,tool));
        assertThat(stages.tool(r.id(),r.contextHash(),t,tool).path("contextHash").asText()).isEqualTo(r.contextHash());
        assertThat(jdbc.queryForObject("select tool_calls from graph_run where id=?",Integer.class,r.id())).isEqualTo(1);
        assertThatThrownBy(()->stages.tool(r.id(),r.contextHash(),t,new ProposalToolService.Request(UUID.randomUUID(),"approve","",1))).isInstanceOf(AnalysisValidationException.class);
        for(int i=0;i<4;i++)stages.reserve(r.id(),r.contextHash(),t,UUID.randomUUID(),1000);
        assertThatThrownBy(()->stages.reserve(r.id(),r.contextHash(),t,UUID.randomUUID(),1)).isInstanceOf(AnalysisConflictException.class);
        assertThat(count("graph_call_reservation")).isEqualTo(5);
        assertThat(caseVersion(f.parser().caseId())).isEqualTo(f.claim().context().path("caseVersion").asLong());
    }
}
