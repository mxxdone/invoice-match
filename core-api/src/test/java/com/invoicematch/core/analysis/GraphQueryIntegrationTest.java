package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.review.application.*;
import com.invoicematch.core.support.TestActors;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** P4-06 read projection over the real PostgreSQL graph fixture. */
class GraphQueryIntegrationTest extends AbstractGraphReviewIntegrationTest {
    @Autowired GraphQueryService queries;
    @Autowired PolicyCatalogService policies;
    @Autowired com.invoicematch.core.purchasingreference.application.PurchasingReferenceService purchasing;

    private GraphViews.View view(Fixture f) {
        return TestActors.call("operator","OPERATOR",()->queries.view(f.parser().caseId(),f.graph().id()));
    }
    private GraphViews.View view(UUID caseId,UUID id) {
        return TestActors.call("operator","OPERATOR",()->queries.view(caseId,id));
    }
    private GraphViews.Page list(UUID caseId) {
        return TestActors.call("operator","OPERATOR",()->queries.list(caseId));
    }
    private JsonNode resumeEvent(Fixture f) throws Exception {
        return json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));
    }
    private int openDispatches(UUID runId) {
        return jdbc.queryForObject("select count(*) from graph_dispatch d join graph_event e on e.id=d.event_id"
            + " where e.run_id=? and d.status in ('READY','CLAIMED')",Integer.class,runId);
    }
    private UUID legacyClone(UUID sourceRunId) {
        jdbc.execute("alter table graph_run disable trigger trg_graph_writer_schema");
        try {
            UUID id=UUID.randomUUID();
            jdbc.update("insert into graph_run(id,invoice_case_id,case_version,evidence_bundle_id,parser_run_id,match_result_id,"
                    + "context,context_hash,workflow_version,graph_version,serializer_version,checkpoint_schema,status,cost_ceiling,active_segment) "
                    + "select ?,invoice_case_id,case_version,evidence_bundle_id,parser_run_id,match_result_id,'{\"legacy\":true}'::jsonb,?,"
                    + "workflow_version,graph_version,serializer_version,2,'QUEUED',1,'START' from graph_run where id=?",
                id,"e".repeat(64),sourceRunId);
            return id;
        } finally {
            jdbc.execute("alter table graph_run enable trigger trg_graph_writer_schema");
        }
    }
    private ReviewSnapshotView freeze(Fixture f) {
        return TestActors.call("approver","APPROVER",()->reviewService.freezeSnapshot(
            new FreezeReviewSnapshotCommand(f.parser().caseId(),UUID.randomUUID().toString(),caseVersion(f.parser().caseId())))).body();
    }

    @Test void waitingViewProjectsFrozenSourcesReasonsAndExactWaitIdentityWithoutMachineState() throws Exception {
        var f=waiting(2);
        var view=view(f);
        assertThat(view.run().status()).isEqualTo("WAITING_HUMAN");
        assertThat(view.run().segment()).isEqualTo("START");
        assertThat(view.run().supported()).isTrue();
        assertThat(view.run().current()).isTrue();
        assertThat(view.pending()).isNotNull();
        assertThat(view.pending().interruptId()).isEqualTo(f.waiting().interruptId());
        assertThat(view.pending().checkpointHash()).isEqualTo(f.waiting().checkpointHash());
        assertThat(view.pending().reviewVersion()).isEqualTo(1);
        assertThat(view.pending().reasonCodes()).containsExactly("AMBIGUOUS_ITEM");
        assertThat(view.pending().documentStageRef()).isNotNull();
        assertThat(view.pending().mappingStageRef()).isNotNull();
        assertThat(view.pending().mapping().path("lines")).hasSize(1);
        assertThat(view.sources()).isNotEmpty();
        assertThat(view.sources()).allSatisfy(source->assertThat(source.text()).isNotBlank());
        assertThat(view.review()).isNull();
        assertThat(view.payload()).isNull();
        String serialized=json.writeValueAsString(view);
        assertThat(serialized).doesNotContain("channel_values","__interrupt__","envelope","execution_token","lease_until");
    }

    @Test void savedReviewTracksQueuedConsumedRunningFailedAndCompletedProposal() throws Exception {
        var f=waiting(2);
        var saved=confirm(f,command(f,"review"));
        var queued=view(f);
        assertThat(queued.run().status()).isEqualTo("QUEUED");
        assertThat(queued.pending()).isNull();
        assertThat(queued.review()).isNotNull();
        assertThat(queued.review().id()).isEqualTo(saved.body().reviewId());
        assertThat(queued.review().actor()).isEqualTo("operator");
        assertThat(queued.review().reason()).isEqualTo("원문과 후보를 확인했습니다.");
        assertThat(queued.review().confirmation().path("documentDecision").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(queued.review().createdAt()).isNotNull();
        assertThat(queued.review().resumeStatus()).isEqualTo("QUEUED");
        assertThat(openDispatches(f.graph().id())).isEqualTo(2);

        var event=resumeEvent(f);
        var claim=delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);
        assertThat(claim.disposition()).isEqualTo("CLAIMED");
        var running=view(f);
        assertThat(running.run().status()).isEqualTo("RUNNING");
        assertThat(running.review().resumeStatus()).isEqualTo("RUNNING");

        assertThat(delivery.failure(f.graph().id(),f.graph().contextHash(),claim.token(),"AI_CONFIGURATION").runStatus()).isEqualTo("FAILED");
        var failed=view(f);
        assertThat(failed.run().status()).isEqualTo("FAILED");
        assertThat(failed.review().resumeStatus()).isEqualTo("FAILED");
    }

    @Test void completedProposalIsReturnedVerbatimWithFrozenSources() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"paper 60 2500"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        var reserved=TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
        var token=graph.claim(reserved.id(),reserved.contextHash()).token();
        String payload="{\"schemaVersion\":\"advisory-proposal-v2\",\"graphVersion\":\"invoice-review-graph-v1\",\"decisions\":[{\"lineNumber\":1,\"action\":\"APPROVE_REVIEW\"}]}";
        store.complete(reserved.id(),payload,"c".repeat(64),token);
        var view=view(f.caseId(),reserved.id());
        assertThat(view.run().status()).isEqualTo("COMPLETED");
        assertThat(view.run().payloadHash()).isEqualTo("c".repeat(64));
        assertThat(view.payload().path("schemaVersion").asText()).isEqualTo("advisory-proposal-v2");
        assertThat(view.payload().path("decisions").get(0).path("action").asText()).isEqualTo("APPROVE_REVIEW");
        assertThat(view.sources()).isNotEmpty();
        assertThat(view.pending()).isNull();
        assertThat(view.review()).isNull();
    }

    @Test void sameCaseMappingChangeLeavesReadAsStaleAndCancelsPendingResume() {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));var snapshot=freeze(f);
        var map=new RecordMappingDecisionCommand(f.parser().caseId(),"map",caseVersion(f.parser().caseId()),snapshot.id(),snapshot.payloadHash(),1,ITEM_A);
        TestActors.run("approver","APPROVER",()->reviewService.recordMapping(map));
        var view=view(f);
        assertThat(view.run().status()).isEqualTo("STALE");
        assertThat(view.review().resumeStatus()).isEqualTo("CANCELLED");
        assertThat(openDispatches(f.graph().id())).isZero();
        assertThat(view.pending()).isNull();
        assertThat(view.payload()).isNull();
    }

    @Test void sharedPolicyChangeOnAnotherCaseConfirmsStaleAndCancelsAtReadTime() {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));
        assertThat(openDispatches(f.graph().id())).isEqualTo(2);
        var other=preparePdfRun(1);
        TestActors.call("operator","OPERATOR",()->policies.publish(other.caseId(),new PolicyCatalogService.Publish(
            "policy",caseVersion(other.caseId()),"CONTRACT-1","graph-policy",1,"검토 기준",LocalDate.of(2020,1,1),LocalDate.of(2030,1,1),
            "manual-fixture","1",List.of(new PolicyCatalogService.ChunkInput(1,1,"사람 확인 필요",new float[]{1,0})))));
        // Another case's transaction never invalidates this case; the read must confirm it.
        assertThat(jdbc.queryForObject("select status from graph_run where id=?",String.class,f.graph().id())).isEqualTo("QUEUED");
        var view=view(f);
        assertThat(view.run().status()).isEqualTo("STALE");
        assertThat(view.run().supported()).isTrue();
        assertThat(view.review().resumeStatus()).isEqualTo("CANCELLED");
        assertThat(openDispatches(f.graph().id())).isZero();
    }

    @Test void sharedPurchasingRefreshConfirmsStaleAtReadTime() {
        var f=waiting(-1);confirm(f,command(f,"confirmed"));
        purchasingResponse(com.invoicematch.core.support.PurchasingPayloads.confirmedPartialReceipt().snapshotVersion(6).toJson());
        purchasing.refresh(new com.invoicematch.core.purchasingreference.application.RefreshPurchaseOrderCommand(
            com.invoicematch.core.shared.domain.PurchaseOrderId.of(PO_ID),com.invoicematch.core.shared.domain.SupplierId.of(SUPPLIER)));
        assertThat(jdbc.queryForObject("select status from graph_run where id=?",String.class,f.graph().id())).isEqualTo("QUEUED");
        var view=view(f);
        assertThat(view.run().status()).isEqualTo("STALE");
        assertThat(view.review().resumeStatus()).isEqualTo("CANCELLED");
        assertThat(openDispatches(f.graph().id())).isZero();
    }

    @Test void unsupportedLegacyStoredSchemaIsMetadataOnlyAndOrderedNewestFirst() {
        var f=waiting(2);var clone=legacyClone(f.graph().id());
        var page=list(f.parser().caseId());
        assertThat(page.history()).hasSize(2);
        assertThat(page.latest().run().id()).isEqualTo(clone);
        assertThat(page.latest().run().supported()).isFalse();
        assertThat(page.latest().pending()).isNull();
        assertThat(page.latest().review()).isNull();
        assertThat(page.latest().payload()).isNull();
        assertThat(page.latest().sources()).isEmpty();
        assertThat(page.history()).extracting(GraphViews.Summary::id).contains(f.graph().id());
    }

    @Test void httpReadsRequireOperatorOrApproverAndNeverCache() throws Exception {
        var f=waiting(2);
        String listPath="/api/invoice-cases/"+f.parser().caseId()+"/graphs";
        String viewPath=listPath+"/"+f.graph().id();
        mvc.perform(get(listPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(listPath).header("Authorization","Bearer "+WORKER_TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get(viewPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(listPath).with(httpBasic("submitter","submitter-pass"))).andExpect(status().isForbidden());
        mvc.perform(get(viewPath).with(httpBasic("submitter","submitter-pass"))).andExpect(status().isForbidden());
        mvc.perform(get(listPath).with(httpBasic("approver","approver-pass"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.latest.run.status").value("WAITING_HUMAN"))
            .andExpect(jsonPath("$.latest.pending.reasonCodes[0]").value("AMBIGUOUS_ITEM"));
        mvc.perform(get(viewPath).with(httpBasic("operator","operator-pass"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.run.id").value(f.graph().id().toString()));
        var other=waiting(2);
        mvc.perform(get("/api/invoice-cases/"+other.parser().caseId()+"/graphs/"+f.graph().id()).with(httpBasic("operator","operator-pass")))
            .andExpect(status().isNotFound());
    }
}
