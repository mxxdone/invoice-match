package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import com.invoicematch.core.document.application.*;
import com.invoicematch.core.document.infrastructure.MinioDocumentStorage;
import com.invoicematch.core.document.persistence.DocumentCleanupStore;
import com.invoicematch.core.support.MinioTestSupport;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class DocumentCleanupIntegrationTest extends AbstractAnalysisIntegrationTest {
    static final String ACCESS="test-"+UUID.randomUUID(),SECRET=UUID.randomUUID().toString();
    static final String BUCKET="invoice-documents";
    static final GenericContainer<?> MINIO=new GenericContainer<>("invoice-match-minio:p2-security-2025-10-15")
        .withExposedPorts(9000).withEnv("MINIO_ROOT_USER",ACCESS).withEnv("MINIO_ROOT_PASSWORD",SECRET)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)).withStartupTimeout(Duration.ofSeconds(60));
    static final MinioClient CLIENT;
    static { MINIO.start();CLIENT=MinioClient.builder().endpoint(endpoint()).credentials(ACCESS,SECRET).region("us-east-1").build();
        try { MinioTestSupport.initializeBucket(CLIENT,BUCKET); } catch(RuntimeException e) { MINIO.stop();throw e; } }
    static String endpoint() { return "http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("document-storage.enabled",()->true);r.add("document-storage.endpoint",DocumentCleanupIntegrationTest::endpoint);
        r.add("document-storage.public-endpoint",DocumentCleanupIntegrationTest::endpoint);
        r.add("document-storage.access-key",()->ACCESS);r.add("document-storage.secret-key",()->SECRET);
        r.add("document.cleanup.enabled",()->true);r.add("document.cleanup.initial-delay",()->"1d");
        r.add("analysis.request.enabled",()->true);
    }
    @AfterAll static void stop() { MINIO.stop(); }
    @Autowired DocumentCleanupService cleanup;
    @Autowired DocumentCleanupStore store;
    @MockitoSpyBean MinioDocumentStorage storage;
    final AtomicBoolean fail=new AtomicBoolean();
    final byte[] bytes="%PDF-1.7\ncleanup fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void deletionBoundary() {
        fail.set(false);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(fail.get()) throw DocumentFailure.storage();return call.callRealMethod();}).when(storage).removeTemporary(any(),any());
    }
    private UUID seed(UUID caseId,int ageHours,boolean registered,String unsafeKey) throws Exception {
        var id=UUID.randomUUID();var revision=currentDraftRevisionId(caseId);
        String key=unsafeKey==null ? "uploads/"+caseId+"/"+id : unsafeKey;
        jdbc.update("insert into document_upload(id,invoice_case_id,draft_revision_id,file_name,media_type,size_bytes,checksum,upload_key,created_at,expires_at)"
            + " values (?,?,?,'a.pdf','application/pdf',?,?,?,clock_timestamp()-cast(? as integer)*interval '1 hour',"
            + "clock_timestamp()-cast(? as integer)*interval '1 hour'+interval '10 minutes')",id,caseId,revision,bytes.length,DocumentPolicy.hash(bytes),key,ageHours,ageHours);
        put(key);
        if(registered) {
            String original="originals/"+caseId+"/"+id;put(original);
            jdbc.update("insert into document(id,object_key,registered_case_version,registered_at) values (?,?,0,clock_timestamp())",id,original);
            jdbc.update("insert into draft_revision_document(draft_revision_id,document_id,invoice_case_id,created_at) values (?,?,?,clock_timestamp())",revision,id,caseId);
        }
        return id;
    }
    private void put(String key) throws Exception { CLIENT.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key).contentType("application/pdf")
        .stream(new ByteArrayInputStream(bytes),bytes.length,-1).build()); }
    private byte[] read(String key) throws Exception {
        try(var object=CLIENT.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) { return object.readAllBytes(); }
    }
    private String status(UUID id) { return jdbc.queryForObject("select status from document_upload_cleanup where upload_id=?",String.class,id); }
    @Test void deletesOnlyExpiredTemporaryCopyAndPreservesFrozenOriginalAndYoungReservation() throws Exception {
        var c=createDraftCase("CLEAN-"+UUID.randomUUID());var old=seed(c,48,true,null);var young=seed(c,0,false,null);
        submit(c,"submit-"+c);
        var before=jdbc.queryForMap("select u.*,d.object_key,d.registered_at from document_upload u join document d on d.id=u.id where u.id=?",old);
        var bundle=jdbc.queryForMap("select * from evidence_bundle where invoice_case_id=?",c);
        var refs=jdbc.queryForList("select * from draft_revision_document where invoice_case_id=?",c);
        assertThat(cleanup.runOnce()).isEqualTo(1);
        assertThatThrownBy(()->read("uploads/"+c+"/"+old)).isInstanceOf(io.minio.errors.ErrorResponseException.class);
        assertThat(read("originals/"+c+"/"+old)).isEqualTo(bytes);
        assertThat(read("uploads/"+c+"/"+young)).isEqualTo(bytes);
        assertThat(jdbc.queryForMap("select u.*,d.object_key,d.registered_at from document_upload u join document d on d.id=u.id where u.id=?",old)).isEqualTo(before);
        assertThat(jdbc.queryForMap("select * from evidence_bundle where invoice_case_id=?",c)).isEqualTo(bundle);
        assertThat(jdbc.queryForList("select * from draft_revision_document where invoice_case_id=?",c)).isEqualTo(refs);
        assertThat(status(old)).isEqualTo("DONE");assertThat(cleanup.runOnce()).isZero();
    }
    @Test void storageFailureKeepsRetryReservationAndNeverChangesUpload() throws Exception {
        var c=createDraftCase("CLEAN-"+UUID.randomUUID());var id=seed(c,48,false,null);
        var before=jdbc.queryForMap("select * from document_upload where id=?",id);fail.set(true);
        assertThat(cleanup.runOnce()).isEqualTo(1);assertThat(status(id)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select next_attempt_at>clock_timestamp() and last_error_code='DOCUMENT_STORAGE_UNAVAILABLE' from document_upload_cleanup where upload_id=?",Boolean.class,id)).isTrue();
        assertThat(read("uploads/"+c+"/"+id)).isEqualTo(bytes);
        assertThat(jdbc.queryForMap("select * from document_upload where id=?",id)).isEqualTo(before);
        fail.set(false);assertThat(cleanup.runOnce()).isZero();
    }
    @Test void unsafeStoredKeyIsBlockedAndOriginalIsUntouched() throws Exception {
        var c=createDraftCase("CLEAN-"+UUID.randomUUID());String key="originals/"+c+"/protected";
        var id=seed(c,48,false,key);assertThat(cleanup.runOnce()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("BLOCKED");assertThat(read(key)).isEqualTo(bytes);
        assertThat(cleanup.runOnce()).isZero();
    }
    @Test void completedFirstHundredReservationsDoNotStarveLaterUploads() throws Exception {
        var c=createDraftCase("CLEAN-"+UUID.randomUUID());var revision=currentDraftRevisionId(c);
        jdbc.update("with rows as (select gen_random_uuid() id from generate_series(1,100))"
            + " insert into document_upload(id,invoice_case_id,draft_revision_id,file_name,media_type,size_bytes,checksum,upload_key,created_at,expires_at)"
            + " select id,?,?,'old.pdf','application/pdf',1,?, 'uploads/'||cast(? as text)||'/'||id::text,"
            + "clock_timestamp()-interval '72 hours',clock_timestamp()-interval '71 hours' from rows",c,revision,"a".repeat(64),c);
        jdbc.update("insert into document_upload_cleanup(upload_id) select id from document_upload where invoice_case_id=?",c);
        jdbc.update("update document_upload_cleanup set status='CLAIMED',claim_token=gen_random_uuid(),lease_until=clock_timestamp()+interval '1 minute',attempt_count=1");
        jdbc.update("update document_upload_cleanup set status='DONE',claim_token=null,lease_until=null,completed_at=clock_timestamp()");
        var next=seed(c,48,false,null);
        assertThat(cleanup.runOnce()).isEqualTo(1);assertThat(status(next)).isEqualTo("DONE");
    }
    @Test void defaultsDisableCleanupAndUnsafeTimingCannotBeConfigured() {
        var defaults=new DocumentCleanupProperties(false,null,null,0,null);
        assertThat(defaults.grace()).isEqualTo(Duration.ofHours(24));
        assertThat(new DocumentCleanupService(defaults,store,storage).runOnce()).isZero();
        assertThatThrownBy(()->new DocumentCleanupProperties(true,Duration.ofMinutes(30),null,1,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new DocumentCleanupProperties(true,null,Duration.ofSeconds(20),1,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void expiredLeaseCanBeReclaimedAndOldTokenCannotFinalize() throws Exception {
        var c=createDraftCase("CLEAN-"+UUID.randomUUID());var id=seed(c,48,false,null);
        var old=store.claim(Duration.ofHours(24),Duration.ofSeconds(1)).orElseThrow();
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while(System.nanoTime()<deadline && Boolean.TRUE.equals(jdbc.queryForObject("select lease_until>clock_timestamp() from document_upload_cleanup where upload_id=?",Boolean.class,id))) Thread.sleep(50);
        assertThat(store.settle(old,"DONE",null,Duration.ZERO)).isFalse();
        assertDatabaseRejects("23514",()->jdbc.update("update document_upload_cleanup set status='DONE',claim_token=null,lease_until=null,completed_at=clock_timestamp() where upload_id=?",id));
        var next=store.claim(Duration.ofHours(24),Duration.ofSeconds(60)).orElseThrow();assertThat(next.token()).isNotEqualTo(old.token());
        assertThat(store.settle(old,"DONE",null,Duration.ZERO)).isFalse();
        storage.removeTemporary(next.caseId(),next.uploadId());assertThat(store.settle(next,"DONE",null,Duration.ZERO)).isTrue();
        assertDatabaseRejects("23000",()->jdbc.update("delete from document_upload_cleanup where upload_id=?",id));
        assertDatabaseRejects("23514",()->jdbc.update("update document_upload_cleanup set status='READY',completed_at=null where upload_id=?",id));
    }
}
