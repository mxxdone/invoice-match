package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.IdempotencyConflictException;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class GraphReviewIntegrationTest extends AbstractGraphReviewIntegrationTest {
    private int reviewsCount() {return count("graph_review");}
    private int eventsCount() {return count("graph_resume_outbox");}
    private JsonNode resumeEvent(Fixture f) throws Exception {return json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox where run_id=?",String.class,f.graph().id()));}
    @Test void exactResumeIdentityConcurrentClaimAndBusyDeferHaveDurableProof() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"review"));var event=resumeEvent(f);
        for(var changed:List.of(((ObjectNode)event.deepCopy()).put("reviewId",UUID.randomUUID().toString()),
                ((ObjectNode)event.deepCopy()).put("checkpointHash","b".repeat(64)),((ObjectNode)event.deepCopy()).put("reviewVersion",2))) {
            assertThatThrownBy(()->delivery.claimResume(f.graph().id(),f.graph().contextHash(),changed)).isInstanceOf(AnalysisConflictException.class);
        }
        assertThat(count("graph_resume_consumption")).isZero();
        GraphExecutionService.Claim owned;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->delivery.claimResume(f.graph().id(),f.graph().contextHash(),event));
            var b=pool.submit(()->delivery.claimResume(f.graph().id(),f.graph().contextHash(),event));
            var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertThat(results).extracting(GraphExecutionService.Claim::disposition).containsExactlyInAnyOrder("CLAIMED","BUSY");
            owned=results.stream().filter(c->c.disposition().equals("CLAIMED")).findFirst().orElseThrow();
        }
        var proof=delivery.defer(f.graph().id(),f.graph().contextHash(),"RESUME",event);
        assertThat(proof).isEqualTo(new GraphDeliveryService.Proof("CHECKPOINTED","RUNNING"));
        assertThat(delivery.defer(f.graph().id(),f.graph().contextHash(),"RESUME",event)).isEqualTo(proof);
        assertThat(jdbc.queryForObject("select count(*) from graph_dispatch where dedup_key like 'defer:%'",Integer.class)).isEqualTo(1);
        assertThat(count("graph_resume_consumption")).isEqualTo(1);
        assertThat(delivery.resume(f.graph().id(),f.graph().contextHash(),owned.token(),event).confirmation()).isEqualTo(f.confirmation());
        assertThat(graph.claim(f.graph().id(),f.graph().contextHash()).disposition()).isEqualTo("ALREADY_FINISHED");
        assertThatThrownBy(()->graph.heartbeat(f.graph().id(),f.graph().contextHash(),UUID.randomUUID())).isInstanceOf(AnalysisConflictException.class);
        assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_resume_consumption"));
    }
    @Test void rateLimitRecoveryIsDueBoundedAndKeepsCumulativeBudget() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"review"));var event=resumeEvent(f);
        var budget=jdbc.queryForMap("select reserved_calls,reserved_tokens,reserved_cost,tool_calls from graph_run where id=?",f.graph().id());
        for(int attempt=1;attempt<=3;attempt++) {
            var claim=delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);assertThat(claim.disposition()).isEqualTo("CLAIMED");
            var result=delivery.failure(f.graph().id(),f.graph().contextHash(),claim.token(),"AI_RATE_LIMIT");
            assertThat(result.runStatus()).isEqualTo(attempt<3?"QUEUED":"FAILED");
            assertThat(delivery.failure(f.graph().id(),f.graph().contextHash(),claim.token(),"AI_RATE_LIMIT")).isEqualTo(result);
            if(attempt<3) {
                assertThat(delivery.claimResume(f.graph().id(),f.graph().contextHash(),event).disposition()).isEqualTo("BUSY");
                long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
                while(!deliveries.due(f.graph().id())) {assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(100);}
            }
        }
        assertThat(count("graph_failure")).isEqualTo(3);assertThat(count("graph_resume_consumption")).isEqualTo(1);
        assertThat(jdbc.queryForMap("select reserved_calls,reserved_tokens,reserved_cost,tool_calls from graph_run where id=?",f.graph().id())).isEqualTo(budget);
        assertThat(delivery.claimResume(f.graph().id(),f.graph().contextHash(),event).disposition()).isEqualTo("ALREADY_FINISHED");
        assertThat(jdbc.queryForObject("select resume_attempts from graph_run where id=?",Integer.class,f.graph().id())).isEqualTo(3);
    }
    @Test void expiredResumeOwnerReclaimsSameConsumptionAndPermanentFailureCancelsDispatch() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"review"));var event=resumeEvent(f);UUID old=UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            store.lock(f.graph().id());store.claim(f.graph().id(),old,java.time.Duration.ofMillis(300));
            deliveries.consume(deliveries.event(f.graph().id(),"RESUME").orElseThrow(),UUID.fromString(event.path("reviewId").asText()),old);
        });
        Thread.sleep(400);
        var claim=delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);assertThat(claim.token()).isNotEqualTo(old);
        assertThat(count("graph_resume_consumption")).isEqualTo(1);
        assertThatThrownBy(()->delivery.resume(f.graph().id(),f.graph().contextHash(),old,event)).isInstanceOf(AnalysisConflictException.class);
        assertThat(delivery.failure(f.graph().id(),f.graph().contextHash(),claim.token(),"AI_CONFIGURATION").runStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from graph_dispatch where status in ('READY','CLAIMED')",Integer.class)).isZero();
    }
    @Test void staleResumeIdentityAcknowledgesOnlyStoredStaleProof() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"review"));var event=resumeEvent(f);
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.parser().caseId(),"new-match")));
        assertThat(delivery.claimResume(f.graph().id(),f.graph().contextHash(),event).disposition()).isEqualTo("ALREADY_FINISHED");
        assertThat(jdbc.queryForObject("select status from graph_run where id=?",String.class,f.graph().id())).isEqualTo("STALE");
        assertThat(count("graph_resume_consumption")).isZero();
        assertThat(delivery.claimResume(f.graph().id(),f.graph().contextHash(),event).disposition()).isEqualTo("ALREADY_FINISHED");
    }
    @Test void busyProofRenewsPublishedRecoveryInsteadOfLosingTheNextDelivery() throws Exception {
        var f=waiting(-1);confirm(f,command(f,"review"));var event=resumeEvent(f);
        delivery.claimResume(f.graph().id(),f.graph().contextHash(),event);
        delivery.defer(f.graph().id(),f.graph().contextHash(),"RESUME",event);
        jdbc.update("update graph_dispatch set status='PUBLISHED',published_at=clock_timestamp() where dedup_key like 'defer:%'");
        assertThat(delivery.defer(f.graph().id(),f.graph().contextHash(),"RESUME",event).runStatus()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status from graph_dispatch where dedup_key like 'defer:%'",String.class)).isEqualTo("READY");
        assertThat(delivery.claimDispatch()).isEmpty();
        assertThat(jdbc.queryForObject("select bool_and(d.next_attempt_at>=g.lease_until) from graph_dispatch d join graph_event e on e.id=d.event_id join graph_run g on g.id=e.run_id where e.segment='RESUME' and d.status='READY'",Boolean.class)).isTrue();
    }
    @Test void atomicSaveReplaysExactResponseWithoutChangingBusinessInputOrResettingBudget() throws Exception {
        var f=waiting(2);var c=command(f,"confirm");var before=jdbc.queryForMap("select context_hash,reserved_calls,reserved_tokens,reserved_cost,stored_bytes,checkpoint_count,write_count from graph_run where id=?",f.graph().id());
        var saved=confirm(f,c);assertThat(saved.status()).isEqualTo(202);assertThat(saved.body().reviewStatus()).isEqualTo("SAVED");assertThat(saved.body().resumeStatus()).isEqualTo("QUEUED");
        assertThat(confirm(f,c)).isEqualTo(saved);assertThat(reviewsCount()).isEqualTo(1);assertThat(eventsCount()).isEqualTo(1);
        assertThatThrownBy(()->TestActors.call("another-operator","OPERATOR",()->reviews.confirm(f.parser().caseId(),f.graph().id(),c)))
            .isInstanceOf(AnalysisConflictException.class).extracting(e->((AnalysisConflictException)e).code()).isEqualTo("GRAPH_REVIEW_CONFLICT");
        assertThat(jdbc.queryForMap("select context_hash,reserved_calls,reserved_tokens,reserved_cost,stored_bytes,checkpoint_count,write_count from graph_run where id=?",f.graph().id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select active_segment from graph_run where id=?",String.class,f.graph().id())).isEqualTo("RESUME");
        assertThat(caseVersion(f.parser().caseId())).isEqualTo(c.expectedCaseVersion());assertThat(count("receipt_allocation")).isZero();assertThat(count("payment_request")).isZero();
        var event=json.readTree(jdbc.queryForObject("select payload::text from graph_resume_outbox",String.class));
        assertThat(event.path("reviewId").asText()).isEqualTo(saved.body().reviewId().toString());assertThat(event.has("confirmation")).isFalse();assertThat(event.has("reason")).isFalse();
        assertThat(graph.claim(f.graph().id(),f.graph().contextHash()).disposition()).isEqualTo("ALREADY_FINISHED");
        var changed=new GraphReviewService.Command(c.requestId(),c.expectedCaseVersion(),c.interruptId(),c.checkpointHash(),1,c.confirmation(),"다른 의견");
        assertThatThrownBy(()->confirm(f,changed)).isInstanceOf(IdempotencyConflictException.class);
        assertDatabaseRejects("P0001",()->jdbc.update("update graph_review set reason='changed'"));assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_resume_outbox"));
        assertDatabaseRejects("23514",()->jdbc.update("insert into graph_resume_outbox select * from graph_resume_outbox"));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.parser().caseId(),"new-match")));
        assertThat(confirm(f,c)).isEqualTo(saved); // Committed replay is not a new authorization to resume changed input.
    }
    @Test void databaseRejectsOrphanReviewAndDuplicateResumeKey() throws Exception {
        var f=waiting(-1);var wait=store.waiting(f.graph().id()).orElseThrow();var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        String confirmation=f.confirmation().toString(),hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(confirmation.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertDatabaseRejects("23514",()->store.queueResume(f.graph().id()));
        assertThatThrownBy(()->tx.executeWithoutResult(s->store.review(f.graph().id(),wait,UUID.randomUUID(),UUID.randomUUID(),"operator","orphan",confirmation,hash,"confirmed")))
            .hasRootCauseInstanceOf(java.sql.SQLException.class).satisfies(e->assertThat(((java.sql.SQLException)org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e)).getSQLState()).isEqualTo("23503"));
        assertThat(reviewsCount()).isZero();
        tx.executeWithoutResult(s->{
            UUID review=UUID.randomUUID(),event=UUID.randomUUID();store.review(f.graph().id(),wait,review,event,"operator","duplicate",confirmation,hash,"confirmed");
            var payload=json.createObjectNode().put("schemaVersion","graph-resume-request-v1").put("eventId",event.toString()).put("graphExecutionId",f.graph().id().toString())
                .put("workflowVersion",GraphRun.WORKFLOW).put("contextHash",f.graph().contextHash()).put("interruptId",wait.interruptId()).put("reviewId",review.toString())
                .put("reviewVersion",1).put("checkpointId",wait.checkpointId().toString()).put("checkpointHash",wait.checkpointHash());
            store.resumeEvent(event,f.graph().id(),review,wait,payload.toString());
            UUID duplicate=UUID.randomUUID();payload.put("eventId",duplicate.toString());
            assertThatThrownBy(()->store.resumeEvent(duplicate,f.graph().id(),review,wait,payload.toString()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class).satisfies(e->assertThat(((java.sql.SQLException)org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e)).getSQLState()).isEqualTo("23505"));
            s.setRollbackOnly();
        });
        assertThat(reviewsCount()).isZero();assertThat(eventsCount()).isZero();
        assertThat(jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname='ux_graph_resume_key'",String.class)).isEqualTo("UNIQUE (run_id, interrupt_id, review_version)");
    }
    @Test void auditFailureRollsBackReviewEventResponseAndQueueTransition() {
        var f=waiting(-1);var c=command(f,"audit-retry");var before=jdbc.queryForMap("select * from graph_run where id=?",f.graph().id());
        jdbc.execute("alter table audit_entry add constraint reject_graph_review_audit check(action<>'AI_GRAPH_REVIEW_SAVED') not valid");
        try {assertThatThrownBy(()->confirm(f,c)).isInstanceOf(RuntimeException.class);} finally {jdbc.execute("alter table audit_entry drop constraint reject_graph_review_audit");}
        assertThat(reviewsCount()).isZero();assertThat(eventsCount()).isZero();assertThat(jdbc.queryForMap("select * from graph_run where id=?",f.graph().id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='graph:review'",Integer.class)).isZero();
        assertThat(confirm(f,c).body().reviewStatus()).isEqualTo("SAVED");
    }
    @Test void concurrentDistinctReviewsHaveOneWinnerAndIdenticalRequestsReplay() throws Exception {
        var f=waiting(-1);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()->race(f,command(f,"first"),gate));var b=pool.submit(()->race(f,command(f,"second"),gate));gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SAVED","CONFLICT");
        } finally {gate.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        assertThat(reviewsCount()).isEqualTo(1);assertThat(eventsCount()).isEqualTo(1);
        var next=waiting(-1);var c=command(next,"same");var identical=Executors.newFixedThreadPool(2);
        try {
            var a=identical.submit(()->confirm(next,c));var b=identical.submit(()->confirm(next,c));assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        } finally {identical.shutdownNow();assertThat(identical.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        assertThat(reviewsCount()).isEqualTo(2);assertThat(eventsCount()).isEqualTo(2);
    }
    private String race(Fixture f,GraphReviewService.Command c,CountDownLatch gate) throws Exception {gate.await(5,TimeUnit.SECONDS);try {confirm(f,c);return "SAVED";}catch(AnalysisConflictException e){assertThat(e.code()).isEqualTo("GRAPH_REVIEW_CONFLICT");return "CONFLICT";}}
    @Test void alteredIdentitySourceCandidateAndOtherCaseAreRejectedWithoutEffects() {
        var f=waiting(2);var c=command(f,"invalid");
        for(var bad:List.of(new GraphReviewService.Command(c.requestId(),c.expectedCaseVersion(),"b".repeat(32),c.checkpointHash(),1,c.confirmation(),c.reason()),
            new GraphReviewService.Command(c.requestId(),c.expectedCaseVersion(),c.interruptId(),"f".repeat(64),1,c.confirmation(),c.reason()),
            new GraphReviewService.Command(c.requestId(),c.expectedCaseVersion(),c.interruptId(),c.checkpointHash(),2,c.confirmation(),c.reason()),
            new GraphReviewService.Command(c.requestId(),c.expectedCaseVersion()-1,c.interruptId(),c.checkpointHash(),1,c.confirmation(),c.reason())))
            assertThatThrownBy(()->confirm(f,bad)).isInstanceOf(AnalysisConflictException.class);
        for(String alteration:List.of("ref","source","candidate","missing")) {
            var payload=f.confirmation().deepCopy();var choice=(ObjectNode)payload.path("itemDecisions").get(0);
            switch(alteration) {case "ref"->payload.put("documentStageRef",UUID.randomUUID().toString());case "source"->((ObjectNode)choice.path("source")).put("end",4);case "candidate"->choice.put("itemId","FOREIGN");case "missing"->payload.putArray("itemDecisions");}
            var bad=new GraphReviewService.Command(c.requestId(),c.expectedCaseVersion(),c.interruptId(),c.checkpointHash(),1,payload,c.reason());
            assertThatThrownBy(()->confirm(f,bad)).isInstanceOf(AnalysisValidationException.class);
        }
        var other=waiting(-1);
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->reviews.confirm(other.parser().caseId(),f.graph().id(),c))).isInstanceOf(AnalysisRunNotFoundException.class);
        assertThat(reviewsCount()).isZero();assertThat(eventsCount()).isZero();assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='graph:review'",Integer.class)).isZero();
    }
    @Test void staleInputAndUnresolvedCandidateCannotBecomeAnInventedMapping() {
        var f=waiting(0);var c=command(f,"unresolved");var invented=f.confirmation().deepCopy();((ObjectNode)invented.path("itemDecisions").get(0)).put("itemId",ITEM_A).put("purchaseOrderLineId","POL-1001-1");
        assertThatThrownBy(()->confirm(f,new GraphReviewService.Command(c.requestId(),c.expectedCaseVersion(),c.interruptId(),c.checkpointHash(),1,invented,c.reason()))).isInstanceOf(AnalysisValidationException.class);
        assertThat(confirm(f,c).body().reviewStatus()).isEqualTo("SAVED");assertThat(jdbc.queryForObject("select confirmation->'itemDecisions'->0->>'itemId' from graph_review",String.class)).isNull();
        var stale=waiting(-1);var staleCommand=command(stale,"stale");TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(stale.parser().caseId(),"changed-input")));
        assertThatThrownBy(()->confirm(stale,staleCommand)).isInstanceOf(AnalysisConflictException.class);assertThat(reviewsCount()).isEqualTo(1);assertThat(eventsCount()).isEqualTo(1);
    }
    @Test void humanHttpSurfaceRequiresOperatorAndReportsSavedRatherThanCompleted() throws Exception {
        var f=waiting(-1);var c=command(f,"http");String path="/api/invoice-cases/"+f.parser().caseId()+"/graphs/"+f.graph().id()+"/reviews";String body=json.writeValueAsString(c);
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Bearer "+WORKER_TOKEN).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        for(String actor:List.of("approver","submitter"))mvc.perform(post(path).with(httpBasic(actor,actor+"-pass")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(httpBasic("operator","operator-pass")).contentType("application/json").content(body)).andExpect(status().isAccepted())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.reviewStatus").value("SAVED")).andExpect(jsonPath("$.resumeStatus").value("QUEUED"));
        mvc.perform(post(path).with(httpBasic("operator","operator-pass")).contentType("application/json").content(body)).andExpect(status().isAccepted());
        assertThat(reviewsCount()).isEqualTo(1);assertThat(eventsCount()).isEqualTo(1);
    }
}
