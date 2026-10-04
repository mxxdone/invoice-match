package com.invoicematch.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class V21UpgradeFromV20MigrationTest extends AbstractPostgresIntegrationTest {
    @Autowired Environment environment;

    @Test void graphMigrationPreservesLegacyFrozenInputsResultsHashesBudgetsAndOutbox() throws Exception {
        String url=environment.getRequiredProperty("spring.datasource.url");
        String user=environment.getRequiredProperty("spring.datasource.username");
        String password=environment.getRequiredProperty("spring.datasource.password");
        String database="p4_01_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String base=url.split("\\?")[0];String target=base.substring(0,base.lastIndexOf('/')+1)+database;
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("create database "+database);
        }
        try {
            var dataSource=new DriverManagerDataSource(target,user,password);
            Flyway.configure().dataSource(dataSource).target("20").load().migrate();
            var jdbc=new JdbcTemplate(dataSource);
            UUID caseId=UUID.randomUUID(),revision=UUID.randomUUID(),bundle=UUID.randomUUID(),parser=UUID.randomUUID(),match=UUID.randomUUID(),run=UUID.randomUUID();
            jdbc.update("insert into invoice_case(id,supplier_id,purchase_order_id,invoice_number,normalized_invoice_number,submitted_by,status,version,created_at,updated_at) values(?,'SUP-1','PO-1001','UPGRADE','UPGRADE','submitter','DRAFT',0,now(),now())",caseId);
            jdbc.update("insert into draft_revision(id,invoice_case_id,revision_number,status,created_at) values(?,?,1,'OPEN',now())",revision,caseId);
            jdbc.update("update invoice_case set current_draft_revision_id=? where id=?",revision,caseId);
            jdbc.update("update draft_revision set status='SEALED',sealed_at=now() where id=?",revision);
            jdbc.update("insert into evidence_bundle(id,invoice_case_id,draft_revision_id,version_number,payload_hash,payload,submitted_at) values(?,?,?,1,'frozen','{\"revisionNumber\":1}',now())",bundle,caseId,revision);
            jdbc.update("insert into analysis_run(id,invoice_case_id,evidence_bundle_id,input_version,evidence_payload_hash,workflow_version,status,created_at,updated_at) values(?,?,?,1,'frozen','document-parser-v1','QUEUED',now(),now())",parser,caseId,bundle);
            jdbc.update("insert into match_result(id,invoice_case_id,evidence_bundle_id,result_hash,payload,created_at,result_number,purchasing_snapshot_version,purchasing_snapshot_hash,mapping_watermark) values(?,?,?,'match','{}',now(),1,0,'purchase',0)",match,caseId,bundle);
            jdbc.update("insert into proposal_run(id,invoice_case_id,evidence_bundle_id,parser_run_id,match_result_id,context_hash,context,workflow_version,status) values(?,?,?,?,?,?,'{\"frozen\":true}','ai-review-v1','QUEUED')",run,caseId,bundle,parser,match,"1".repeat(64));
            jdbc.update("insert into proposal_request_outbox(id,payload,status) values(?,'{\"eventId\":\"immutable\"}','READY')",run);
            jdbc.update("insert into proposal_step(run_id,stage,payload,payload_hash) values(?,'document','{\"partial\":true}',?)",run,"2".repeat(64));
            jdbc.update("insert into proposal_call_reservation(run_id,request_id,reserved_tokens) values(?,?,100)",run,UUID.randomUUID());
            for(int attempt=1;attempt<=3;attempt++) {
                jdbc.update("update proposal_run set status='RUNNING',execution_token=?,execution_attempt=?,lease_until=clock_timestamp()+interval '1 second' where id=?",UUID.randomUUID(),attempt,run);
                if(attempt==1) jdbc.update("update proposal_run set reserved_calls=1,reserved_tokens=100 where id=?",run);
                long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                while(Boolean.TRUE.equals(jdbc.queryForObject("select lease_until>clock_timestamp() from proposal_run where id=?",Boolean.class,run))) {
                    assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(25);
                }
            }
            var before=jdbc.queryForMap("select *,context::text as frozen from proposal_run where id=?",run);
            var step=jdbc.queryForMap("select *,payload::text as frozen from proposal_step where run_id=?",run);
            var call=jdbc.queryForMap("select * from proposal_call_reservation where run_id=?",run);
            var event=jdbc.queryForMap("select *,payload::text as frozen from proposal_request_outbox where id=?",run);
            UUID completedMatch=UUID.randomUUID(),completedRun=UUID.randomUUID();
            jdbc.update("insert into match_result(id,invoice_case_id,evidence_bundle_id,result_hash,payload,created_at,result_number,purchasing_snapshot_version,purchasing_snapshot_hash,mapping_watermark) values(?,?,?,'completed-match','{}',now(),2,0,'purchase',0)",completedMatch,caseId,bundle);
            jdbc.update("insert into proposal_run(id,invoice_case_id,evidence_bundle_id,parser_run_id,match_result_id,context_hash,context,workflow_version,status) values(?,?,?,?,?,?,'{\"completedInput\":true}','ai-review-v1','QUEUED')",completedRun,caseId,bundle,parser,completedMatch,"3".repeat(64));
            jdbc.update("update proposal_run set status='RUNNING',execution_token=?,execution_attempt=1,lease_until=clock_timestamp()+interval '2 minutes' where id=?",UUID.randomUUID(),completedRun);
            jdbc.update("insert into proposal(id,invoice_case_id,evidence_bundle_id,match_result_id,context_hash,payload,payload_hash) values(?,?,?,?,?,'{\"legacy\":true,\"version\":1}',?)",completedRun,caseId,bundle,completedMatch,"3".repeat(64),"4".repeat(64));
            jdbc.update("update proposal_run set status='COMPLETED',execution_token=null,lease_until=null where id=?",completedRun);
            var completed=jdbc.queryForMap("select *,payload::text as frozen from proposal where id=?",completedRun);
            Flyway.configure().dataSource(dataSource).target("21").load().migrate();
            assertThat(jdbc.queryForMap("select *,context::text as frozen from proposal_run where id=?",run)).isEqualTo(before);
            assertThat(jdbc.queryForMap("select *,payload::text as frozen from proposal_step where run_id=?",run)).isEqualTo(step);
            assertThat(jdbc.queryForMap("select * from proposal_call_reservation where run_id=?",run)).isEqualTo(call);
            assertThat(jdbc.queryForMap("select *,payload::text as frozen from proposal_request_outbox where id=?",run)).isEqualTo(event);
            assertThat(jdbc.queryForMap("select *,payload::text as frozen from proposal where id=?",completedRun)).isEqualTo(completed);
            assertThat(jdbc.queryForObject("select count(*) from graph_run",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from graph_checkpoint",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from proposal_failure",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from proposal_dispatch",Integer.class)).isZero();
            assertDatabaseRejects("23514",()->jdbc.update("update proposal_run set reserved_tokens=0 where id=?",run));
            jdbc.update("update proposal_run set status='FAILED',execution_token=null,lease_until=null,error_code='LEASE_EXPIRED' where id=?",run);
            assertThat(jdbc.queryForObject("select reserved_tokens from proposal_run where id=?",Integer.class,run)).isEqualTo(100);
            assertDatabaseRejects("23000",()->jdbc.update("update proposal_run set status='QUEUED' where id=?",run));
        } finally {
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
                statement.execute("drop database "+database+" with (force)");
            }
        }
    }
}
