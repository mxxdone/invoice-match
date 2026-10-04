package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class GraphPersistenceIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @DynamicPropertySource static void graphProperties(DynamicPropertyRegistry r) {
        r.add("analysis.graph.enabled",()->true);r.add("analysis.graph.cost-ceiling",()->"1.0");
    }
    @Autowired GraphExecutionService graph;
    @Autowired GraphStore graphStore;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    private record Fixture(RunFixture parser, GraphExecutionService.Reserved graph) {}
    private Fixture ready() {
        var f=preparePdfRun(1);var claim=claim(f);var doc=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,claim.claimToken(),doc.documentId(),"SUCCESS",pdfResult(doc,"A4 paper 60 2500"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        var reserved=TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),new ProposalService.ReserveCommand(UUID.randomUUID().toString(),caseVersion(f.caseId())))).body();
        return new Fixture(f,reserved);
    }
    private GraphExecutionService.Claim start(Fixture f) {return graph.claim(f.graph.id(),f.graph.contextHash());}
    private JsonNode encode(JsonNode value) {
        if(value.isObject()) {
            var obj=json.createObjectNode();value.properties().forEach(e->obj.set(e.getKey(),encode(e.getValue())));
            return json.createArrayNode().add("dict").add(obj);
        }
        if(value.isArray()) {var array=json.createArrayNode();value.forEach(v->array.add(encode(v)));return json.createArrayNode().add("list").add(array);}
        return json.createArrayNode().add("scalar").add(value);
    }
    private GraphCommands.Checkpoint checkpoint(Fixture f, UUID id, UUID parent) {
        var body=json.createObjectNode().put("v",GraphRun.SCHEMA).put("id",id.toString()).put("ts",Instant.now().toString());
        body.putObject("channel_values").put("graphExecutionId",f.graph.id().toString()).put("contextHash",f.graph.contextHash());
        body.putObject("channel_versions").put("graphExecutionId",1);body.putObject("versions_seen");body.putNull("updated_channels");
        var metadata=json.createObjectNode().put("source","loop").put("step",1);metadata.putObject("parents");
        return new GraphCommands.Checkpoint(f.graph.id(),GraphRun.GRAPH,GraphRun.SERIALIZER,GraphRun.SCHEMA,id,parent,encode(body),metadata,json.createObjectNode().put("graphExecutionId",1));
    }
    private GraphCommands.Write interrupt(Fixture f, UUID checkpoint, UUID task, int version, String previousHash) {
        var value=json.createObjectNode().put("graphExecutionId",f.graph.id().toString());value.putArray("reasonCodes").add("AMBIGUOUS_ITEM");
        var dto=json.createObjectNode().put("id","a".repeat(32));dto.set("value",encode(value));
        var payload=json.createArrayNode().add("tuple").add(json.createArrayNode().add(json.createArrayNode().add("interrupt").add(dto)));
        return new GraphCommands.Write(checkpoint,task,-3,version,previousHash,"__interrupt__","",payload);
    }
    private GraphCommands.Wait waitCommand(UUID checkpoint, String hash, GraphCommands.Write write, String writeHash) {
        return new GraphCommands.Wait(checkpoint,hash,write.taskId(),write.version(),writeHash,"a".repeat(32));
    }
    private void conflict(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(AnalysisConflictException.class)
                .extracting(e->((AnalysisConflictException)e).code()).isEqualTo(code);
    }
    private int countGraph(String table) {return jdbc.queryForObject("select count(*) from "+table,Integer.class);}

    @Test void checkpointWritesReplayAndVersionedReservedSlotsAreImmutable() {
        var f=ready();var claim=start(f);UUID cp=UUID.randomUUID(),task=UUID.randomUUID();
        var command=checkpoint(f,cp,null);var saved=graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command);
        assertThat(graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command).disposition()).isEqualTo("REPLAYED");
        var write=interrupt(f,cp,task,1,null);var first=graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(write)).getFirst();
        assertThat(graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(write)).getFirst().disposition()).isEqualTo("REPLAYED");
        var second=interrupt(f,cp,task,2,first.hash());graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(second));
        assertThat(countGraph("graph_pending_write")).isEqualTo(2);
        assertThat(graph.read(f.graph.id(),f.graph.contextHash(),claim.token(),cp).writes().getFirst().version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select checkpoint_count from graph_run",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select write_count from graph_run",Integer.class)).isEqualTo(2);
        assertDatabaseRejects("P0001",()->jdbc.update("update graph_checkpoint set payload_hash=? where run_id=?","f".repeat(64),f.graph.id()));
        assertDatabaseRejects("P0001",()->jdbc.update("delete from graph_pending_write where run_id=?",f.graph.id()));
        ((ObjectNode)command.metadata()).put("step",2);
        conflict("GRAPH_CHECKPOINT_CONFLICT",()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command));
        assertThat(graph.read(f.graph.id(),f.graph.contextHash(),claim.token(),null).hash()).isEqualTo(saved.hash());
        var wrongPrevious=interrupt(f,cp,task,3,"b".repeat(64));
        conflict("GRAPH_WRITE_VERSION_CONFLICT",()->graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(wrongPrevious)));
        assertThat(countGraph("graph_pending_write")).isEqualTo(2);
    }
    @Test void waitingRequiresExactPersistedInterruptAndClearsLeaseWithDurableReplay() {
        var f=ready();var claim=start(f);UUID cp=UUID.randomUUID();
        var saved=graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),checkpoint(f,cp,null));
        var write=interrupt(f,cp,UUID.randomUUID(),1,null);
        conflict("GRAPH_WAIT_CONFLICT",()->graph.waitForHuman(f.graph.id(),f.graph.contextHash(),claim.token(),waitCommand(cp,saved.hash(),write,"b".repeat(64))));
        assertThat(countGraph("graph_interrupt")).isZero();
        var stored=graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(write)).getFirst();
        var wait=waitCommand(cp,saved.hash(),write,stored.hash());
        conflict("GRAPH_WAIT_CONFLICT",()->graph.waitForHuman(f.graph.id(),f.graph.contextHash(),claim.token(),new GraphCommands.Wait(cp,"f".repeat(64),write.taskId(),1,stored.hash(),"a".repeat(32))));
        var proof=graph.waitForHuman(f.graph.id(),f.graph.contextHash(),claim.token(),wait);
        assertThat(graph.waitForHuman(f.graph.id(),f.graph.contextHash(),claim.token(),wait)).isEqualTo(proof);
        var before=jdbc.queryForMap("select * from graph_run");
        for(int i=0;i<3;i++)assertThat(start(f).disposition()).isEqualTo("WAITING_HUMAN");
        assertThat(jdbc.queryForMap("select * from graph_run")).isEqualTo(before);
        assertThat(before.get("status")).isEqualTo("WAITING_HUMAN");
        assertThat(before.get("execution_token")).isNull();assertThat(before.get("lease_until")).isNull();
        conflict("LEASE_CONFLICT",()->graph.read(f.graph.id(),f.graph.contextHash(),claim.token(),cp));
        assertDatabaseRejects("23514",()->jdbc.update("update graph_run set status='QUEUED' where id=?",f.graph.id()));
        assertDatabaseRejects("23514",()->jdbc.update("update graph_run set reserved_tokens=1 where id=?",f.graph.id()));
        assertThat(countGraph("payment_request")).isZero();
    }
    @Test void actualLeaseExpiryRejectsOldTokenAndReclaimReusesCheckpointWithoutBudgetReset() throws Exception {
        var f=ready();UUID token=UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status->{graphStore.lock(f.graph.id());graphStore.claim(f.graph.id(),token,Duration.ofSeconds(2));});
        UUID cp=UUID.randomUUID();var command=checkpoint(f,cp,null);
        graph.checkpoint(f.graph.id(),f.graph.contextHash(),token,command);
        jdbc.update("update graph_run set reserved_calls=1,reserved_tokens=100,tool_calls=1,reserved_cost=0.01 where id=?",f.graph.id());
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(Boolean.TRUE.equals(jdbc.queryForObject("select lease_until>clock_timestamp() from graph_run",Boolean.class))) {
            assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(25);
        }
        conflict("LEASE_CONFLICT",()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),token,command));
        conflict("LEASE_CONFLICT",()->graph.read(f.graph.id(),f.graph.contextHash(),token,cp));
        conflict("LEASE_CONFLICT",()->graph.heartbeat(f.graph.id(),f.graph.contextHash(),token));
        var reclaimed=start(f);assertThat(reclaimed.token()).isNotEqualTo(token);
        assertThat(graph.checkpoint(f.graph.id(),f.graph.contextHash(),reclaimed.token(),command).disposition()).isEqualTo("REPLAYED");
        var write=interrupt(f,cp,UUID.randomUUID(),1,null);
        var stored=graph.writes(f.graph.id(),f.graph.contextHash(),reclaimed.token(),List.of(write)).getFirst();
        graph.waitForHuman(f.graph.id(),f.graph.contextHash(),reclaimed.token(),waitCommand(cp,graph.read(f.graph.id(),f.graph.contextHash(),reclaimed.token(),cp).hash(),write,stored.hash()));
        var row=jdbc.queryForMap("select * from graph_run");
        assertThat(row.get("start_attempts")).isEqualTo(2);assertThat(row.get("resume_attempts")).isEqualTo(0);
        assertThat(row.get("reserved_tokens")).isEqualTo(100);assertThat(row.get("reserved_calls")).isEqualTo(1);
        assertThat(row.get("tool_calls")).isEqualTo(1);assertThat(row.get("checkpoint_count")).isEqualTo(1);
    }
    @Test void crossThreadFakeParentVersionAndSdkObjectsAreRejectedWithoutEffects() {
        var f=ready();var claim=start(f);UUID cp=UUID.randomUUID();var command=checkpoint(f,cp,null);
        var other=ready();var otherClaim=start(other);
        assertThatThrownBy(()->graph.checkpoint(other.graph.id(),other.graph.contextHash(),otherClaim.token(),command)).isInstanceOf(AnalysisValidationException.class);
        var fakeParent=checkpoint(f,cp,UUID.randomUUID());
        conflict("GRAPH_PARENT_CONFLICT",()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),fakeParent));
        var wrongVersion=new GraphCommands.Checkpoint(f.graph.id(),"invoice-review-graph-v0",GraphRun.SERIALIZER,GraphRun.SCHEMA,cp,null,command.body(),command.metadata(),command.newVersions());
        assertThatThrownBy(()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),wrongVersion)).isInstanceOf(AnalysisValidationException.class);
        var unsafe=new GraphCommands.Checkpoint(f.graph.id(),GraphRun.GRAPH,GraphRun.SERIALIZER,GraphRun.SCHEMA,cp,null,json.createArrayNode().add("pickle").add("payload"),command.metadata(),command.newVersions());
        assertThatThrownBy(()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),unsafe)).isInstanceOf(AnalysisValidationException.class);
        assertThat(countGraph("graph_checkpoint")).isZero();
        graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command);
        conflict("GRAPH_CHECKPOINT_MISSING",()->graph.read(other.graph.id(),other.graph.contextHash(),otherClaim.token(),cp));
        conflict("GRAPH_INPUT_MISMATCH",()->graph.claim(f.graph.id(),other.graph.contextHash()));
    }
    @Test void batchFailureRollsBackEarlierWriteAndSqlConstraintsEnforceZeroEffects() throws Exception {
        var f=ready();var claim=start(f);UUID cp=UUID.randomUUID();var command=checkpoint(f,cp,null);
        graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command);
        var write=interrupt(f,cp,UUID.randomUUID(),1,null);
        var missing=interrupt(f,UUID.randomUUID(),UUID.randomUUID(),1,null);
        var before=jdbc.queryForMap("select checkpoint_count,write_count,stored_bytes from graph_run");
        conflict("GRAPH_CHECKPOINT_MISSING",()->graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(write,missing)));
        assertThat(jdbc.queryForMap("select checkpoint_count,write_count,stored_bytes from graph_run")).isEqualTo(before);
        assertThat(countGraph("graph_pending_write")).isZero();
        String envelope=json.writeValueAsString(command);
        assertDatabaseRejects("23505",()->jdbc.update("insert into graph_checkpoint(run_id,checkpoint_id,graph_version,envelope,payload_hash,execution_token) values(?,?,?,cast(? as jsonb),?,?)",f.graph.id(),cp,GraphRun.GRAPH,envelope,"a".repeat(64),claim.token()));
        UUID missingParent=UUID.randomUUID(),freshId=UUID.randomUUID();
        String freshEnvelope=json.writeValueAsString(checkpoint(f,freshId,missingParent));
        assertDatabaseRejects("23503",()->jdbc.update("insert into graph_checkpoint(run_id,checkpoint_id,graph_version,parent_id,envelope,payload_hash,execution_token) values(?,?,?,?,cast(? as jsonb),?,?)",f.graph.id(),freshId,GraphRun.GRAPH,missingParent,freshEnvelope,"a".repeat(64),claim.token()));
        assertDatabaseRejects("23514",()->jdbc.update("update graph_run set reserved_calls=6 where id=?",f.graph.id()));
        assertDatabaseRejects("23000",()->jdbc.update("update graph_run set context_hash=? where id=?","b".repeat(64),f.graph.id()));
        assertThat(jdbc.queryForMap("select checkpoint_count,write_count,stored_bytes from graph_run")).isEqualTo(before);
    }
    @Test void staleInputCannotReadWriteOrEnterHumanWaiting() {
        var f=ready();var claim=start(f);UUID cp=UUID.randomUUID();
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.parser.caseId(),UUID.randomUUID().toString())));
        conflict("STALE_INPUT",()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),checkpoint(f,cp,null)));
        assertThat(start(f).disposition()).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("select status from graph_run",String.class)).isEqualTo("STALE");
        assertThat(countGraph("graph_checkpoint")).isZero();
    }
    @Test void machineSurfaceUsesBearerOnlyExactRoutesAndNoHumanStateOrNamespace() throws Exception {
        var f=ready();String path="/internal/graph-runs/"+f.graph.id();
        String claimBody=json.createObjectNode().put("contextHash",f.graph.contextHash()).toString();
        mvc.perform(post(path+"/claim").contentType("application/json").content(claimBody)).andExpect(status().isUnauthorized());
        mvc.perform(post(path+"/claim").with(httpBasic("operator","operator-pass")).contentType("application/json").content(claimBody)).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/claim").header("Authorization","Bearer "+WORKER_TOKEN)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/sql").header("Authorization","Bearer "+WORKER_TOKEN).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var response=mvc.perform(post(path+"/claim").header("Authorization","Bearer "+WORKER_TOKEN).contentType("application/json").content(claimBody))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        UUID token=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("token").asText());
        var c=checkpoint(f,UUID.randomUUID(),null);var request=json.createObjectNode().put("contextHash",f.graph.contextHash()).put("token",token.toString());request.set("checkpoint",json.valueToTree(c));
        mvc.perform(post(path+"/checkpoints").header("Authorization","Bearer "+WORKER_TOKEN).contentType("application/json").content(request.toString())).andExpect(status().isOk());
        ((ObjectNode)request.path("checkpoint")).put("namespace","other");
        mvc.perform(post(path+"/checkpoints").header("Authorization","Bearer "+WORKER_TOKEN).contentType("application/json").content(request.toString())).andExpect(status().isBadRequest());
        assertThat(countGraph("graph_checkpoint")).isEqualTo(1);
    }
    @Test void concurrentIdenticalSaveHasOneCheckpointAndOneReplay() throws Exception {
        var f=ready();var claim=start(f);var command=checkpoint(f,UUID.randomUUID(),null);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command));
            var second=pool.submit(()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command));
            assertThat(List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS).disposition(),second.get(10,java.util.concurrent.TimeUnit.SECONDS).disposition()))
                    .containsExactlyInAnyOrder("ACCEPTED","REPLAYED");
        }
        assertThat(countGraph("graph_checkpoint")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select checkpoint_count from graph_run",Integer.class)).isEqualTo(1);
    }
    @Test void waitingTransitionFailureRollsBackInterruptAndPreservesCheckpointAndLease() {
        var f=ready();var claim=start(f);UUID cp=UUID.randomUUID();
        var saved=graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),checkpoint(f,cp,null));
        var write=interrupt(f,cp,UUID.randomUUID(),1,null);
        var stored=graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(write)).getFirst();
        var before=jdbc.queryForMap("select * from graph_run");
        jdbc.execute("create function test_graph_wait_failure() returns trigger language plpgsql as $$ begin if NEW.status='WAITING_HUMAN' then raise exception 'injected wait failure' using errcode='23514'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_graph_wait_failure before update on graph_run for each row execute function test_graph_wait_failure()");
        try {
            assertThatThrownBy(()->graph.waitForHuman(f.graph.id(),f.graph.contextHash(),claim.token(),waitCommand(cp,saved.hash(),write,stored.hash())))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(countGraph("graph_interrupt")).isZero();
            assertThat(jdbc.queryForMap("select * from graph_run")).isEqualTo(before);
            assertThat(countGraph("graph_checkpoint")).isEqualTo(1);assertThat(countGraph("graph_pending_write")).isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger test_graph_wait_failure on graph_run");jdbc.execute("drop function test_graph_wait_failure()");
        }
        graph.waitForHuman(f.graph.id(),f.graph.contextHash(),claim.token(),waitCommand(cp,saved.hash(),write,stored.hash()));
    }
    @Test void storageCountSizeAndClosedJsonLimitsAreEnforcedAtApiAndDatabase() throws Exception {
        var f=ready();var claim=start(f);UUID cp=UUID.randomUUID();
        var command=checkpoint(f,cp,null);graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),command);
        var tooLarge=new GraphCommands.Write(cp,UUID.randomUUID(),0,1,null,"mappingStageRef","",json.createArrayNode().add("scalar").add("x".repeat(GraphRun.MAX_BYTES)));
        conflict("GRAPH_STORAGE_LIMIT",()->graph.writes(f.graph.id(),f.graph.contextHash(),claim.token(),List.of(tooLarge)));
        assertThat(countGraph("graph_pending_write")).isZero();
        String unsafe="[\"pickle\",\"payload\"]";
        assertDatabaseRejects("23514",()->jdbc.update("insert into graph_pending_write(run_id,checkpoint_id,task_id,write_index,version_number,channel,payload,payload_hash,execution_token) values(?,?,?,0,1,'mappingStageRef',cast(? as jsonb),?,?)",f.graph.id(),cp,UUID.randomUUID(),unsafe,"a".repeat(64),claim.token()));
        UUID parent=cp;
        for(int i=1;i<GraphRun.MAX_CHECKPOINTS;i++) {
            UUID next=UUID.randomUUID();graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),checkpoint(f,next,parent));parent=next;
        }
        UUID last=parent,newId=UUID.randomUUID();var excess=checkpoint(f,newId,last);
        conflict("GRAPH_STORAGE_LIMIT",()->graph.checkpoint(f.graph.id(),f.graph.contextHash(),claim.token(),excess));
        String envelope=json.writeValueAsString(excess);
        var before=jdbc.queryForMap("select checkpoint_count,write_count,stored_bytes from graph_run");
        assertDatabaseRejects("23514",()->jdbc.update("insert into graph_checkpoint(run_id,checkpoint_id,graph_version,parent_id,envelope,payload_hash,execution_token) values(?,?,?,?,cast(? as jsonb),?,?)",f.graph.id(),newId,GraphRun.GRAPH,last,envelope,"a".repeat(64),claim.token()));
        assertThat(jdbc.queryForMap("select checkpoint_count,write_count,stored_bytes from graph_run")).isEqualTo(before);
        assertThat(countGraph("graph_checkpoint")).isEqualTo(GraphRun.MAX_CHECKPOINTS);
        assertDatabaseRejects("23514",()->jdbc.update("update graph_run set stored_bytes=? where id=?",GraphRun.MAX_TOTAL_BYTES+1,f.graph.id()));
    }
    @Test void leaseExpiryDuringScopeLockCannotAuthorizeCheckpointRead() throws Exception {
        var f=ready();UUID token=UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(s->{graphStore.lock(f.graph.id());graphStore.claim(f.graph.id(),token,Duration.ofSeconds(3));});
        UUID cp=UUID.randomUUID();graph.checkpoint(f.graph.id(),f.graph.contextHash(),token,checkpoint(f,cp,null));
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var blocker=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(s->{
                jdbc.queryForList("select purchase_order_id from purchase_order_snapshot where purchase_order_id=? for update",PO_ID);
                locked.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Scope lock timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            try {
                assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var reader=pool.submit(()->graph.read(f.graph.id(),f.graph.contextHash(),token,cp));
                long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                while(jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%purchase_order_snapshot%for share%'",Integer.class)==0) {
                    assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(25);
                }
                while(Boolean.TRUE.equals(jdbc.queryForObject("select lease_until>clock_timestamp() from graph_run",Boolean.class))) {
                    assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(25);
                }
                release.countDown();blocker.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertThatThrownBy(()->reader.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(AnalysisConflictException.class)
                        .satisfies(e->assertThat(((AnalysisConflictException)e.getCause()).code()).isEqualTo("LEASE_CONFLICT"));
            } finally { release.countDown(); }
        }
    }
}
