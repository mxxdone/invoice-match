package com.invoicematch.core.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.infrastructure.MinioDocumentStorage;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.MinioTestSupport;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Independent HTTP/storage/SQL observations, using real MinIO and PostgreSQL. */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import(DocumentUploadIntegrationTest.TimeConfig.class)
class DocumentUploadIntegrationTest extends AbstractPostgresIntegrationTest {
    static final String ACCESS = "test-" + UUID.randomUUID();
    static final String SECRET = UUID.randomUUID().toString();
    static final GenericContainer<?> MINIO = new GenericContainer<>("invoice-match-minio:p2-security-2025-10-15")
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)).withStartupTimeout(Duration.ofSeconds(60));
    static final MinioClient CLIENT;
    static {
        MINIO.start();
        CLIENT = MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).region("us-east-1").build();
        try { MinioTestSupport.initializeBucket(CLIENT, "invoice-documents"); }
        catch (RuntimeException e) { MINIO.stop(); throw e; }
    }
    static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    @DynamicPropertySource static void storageProperties(DynamicPropertyRegistry r) {
        r.add("document-storage.enabled", () -> true);
        r.add("document-storage.endpoint", DocumentUploadIntegrationTest::endpoint);
        r.add("document-storage.public-endpoint", DocumentUploadIntegrationTest::endpoint);
        r.add("document-storage.access-key", () -> ACCESS);
        r.add("document-storage.secret-key", () -> SECRET);
    }
    @AfterAll static void stopStorage() { MINIO.stop(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoSpyBean MinioDocumentStorage storage;
    final AtomicReference<Runnable> afterRead = new AtomicReference<>(() -> {});
    UUID caseId;
    UUID draftId;
    final byte[] pdf = "%PDF-1.7\nfixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock documentTestClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now = Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @BeforeEach void setup() {
        clock.now = Instant.now();
        afterRead.set(() -> {});
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        caseId = UUID.randomUUID(); draftId = UUID.randomUUID();
        seed(caseId, draftId, "submitter");
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var bytes = i.callRealMethod(); afterRead.get().run(); return bytes;
        }).when(storage).readVerified(any());
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return i.callRealMethod();
        }).when(storage).writeOriginal(anyString(), any(), anyString());
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return i.callRealMethod();
        }).when(storage).removeOriginal(anyString());
    }
    void seed(UUID id, UUID revision, String owner) {
        jdbc.update("insert into invoice_case (id,supplier_id,purchase_order_id,invoice_number,normalized_invoice_number,"
                + "submitted_by,status,version,created_at,updated_at) values (?,'SUP-1','PO-1001',?,? ,?,'DRAFT',0,now(),now())",
                id, id.toString(), id.toString(), owner);
        jdbc.update("insert into draft_revision (id,invoice_case_id,revision_number,status,created_at) values (?, ?,1,'OPEN',now())",
                revision, id);
        jdbc.update("update invoice_case set current_draft_revision_id = ? where id = ?", revision, id);
    }
    String base() { return "/api/invoice-cases/" + caseId + "/documents"; }
    Map<String, Object> reserveBody(String req, byte[] bytes, String name, String type, long version) {
        return Map.of("requestId", req, "expectedCaseVersion", version, "draftRevisionId", draftId,
                "fileName", name, "mediaType", type, "sizeBytes", bytes.length, "checksum", DocumentPolicy.hash(bytes));
    }
    Map<String, Object> completeBody(JsonNode upload, String req, long version, byte[] bytes) {
        return Map.of("requestId", req, "expectedCaseVersion", version, "draftRevisionId", draftId,
                "documentId", upload.get("documentId").asText(), "checksum", DocumentPolicy.hash(bytes));
    }
    MvcResult postBody(String path, Object body, String actor, String role) throws Exception {
        return mvc.perform(post(path).with(user(actor).roles(role)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andReturn();
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    JsonNode reserve(String req, byte[] bytes, String name, String type) throws Exception {
        var result = postBody(base()+"/presign", reserveBody(req, bytes, name, type, 0), "submitter", "SUBMITTER");
        assertThat(result.getResponse().getStatus()).isEqualTo(201); return body(result);
    }
    void put(JsonNode upload, byte[] bytes, String type) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(upload.get("uploadUrl").asText()))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", type).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }
    long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    byte[] original(JsonNode upload) throws Exception {
        String key = jdbc.queryForObject("select object_key from document where id = ?", String.class,
                UUID.fromString(upload.get("documentId").asText()));
        try (var object = CLIENT.getObject(GetObjectArgs.builder().bucket("invoice-documents").object(key).build())) {
            return object.readAllBytes();
        }
    }
    @Test void completesPdfAndReplaysEvenAfterTemporaryObjectIsOverwrittenAndCaseIsSealed() throws Exception {
        var upload = reserve("reserve", pdf, "invoice.pdf", DocumentPolicy.PDF);
        put(upload, pdf, DocumentPolicy.PDF);
        var request = completeBody(upload, "complete", 0, pdf);
        var first = postBody(base()+"/complete", request, "submitter", "SUBMITTER");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(first).get("caseVersion").asLong()).isEqualTo(1);
        put(upload, "%PDF-replaced".getBytes(), DocumentPolicy.PDF);
        assertThat(original(upload)).isEqualTo(pdf);
        jdbc.update("update draft_revision set status='SEALED',sealed_at=now() where id=?", draftId);
        clock.now = clock.now.plusSeconds(601);
        var replay = postBody(base()+"/complete", request, "submitter", "SUBMITTER");
        assertThat(body(replay)).isEqualTo(body(first));
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(postBody(base()+"/complete",completeBody(upload,"new-retry",0,pdf),"submitter","SUBMITTER")))
                .isEqualTo(body(first));
        assertThat(count("document")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action='DOCUMENT_REGISTERED'", Long.class)).isEqualTo(1);
        String audit = jdbc.queryForObject("select after_state::text from audit_entry where action='DOCUMENT_REGISTERED'", String.class);
        assertThat(audit).doesNotContain("X-Amz", "uploadUrl", "objectKey", SECRET);
        var listed = mvc.perform(get(base()).with(user("approver").roles("APPROVER"))).andReturn();
        assertThat(listed.getResponse().getContentAsString()).doesNotContain("originals/", "uploads/", "X-Amz");
        assertThat(body(listed).get("items").size()).isEqualTo(1);
    }
    @Test void presignReplayIsExactAndChangedPayloadConflicts() throws Exception {
        var request = reserveBody("same", pdf, "invoice.pdf", DocumentPolicy.PDF, 0);
        var first = postBody(base()+"/presign", request, "submitter", "SUBMITTER");
        var second = postBody(base()+"/presign", request, "submitter", "SUBMITTER");
        assertThat(body(second)).isEqualTo(body(first));
        var changed = new HashMap<>(request); changed.put("fileName", "other.pdf");
        assertThat(postBody(base()+"/presign", changed, "submitter", "SUBMITTER").getResponse().getStatus()).isEqualTo(409);
        assertThat(count("document_upload")).isEqualTo(1);
    }
    @Test void wrongOwnerRolesAndCrossCaseDocumentAreDenied() throws Exception {
        var request = reserveBody("reserve", pdf, "invoice.pdf", DocumentPolicy.PDF, 0);
        for (String[] actor : List.of(new String[]{"submitter2","SUBMITTER"}, new String[]{"approver","APPROVER"}, new String[]{"operator","OPERATOR"})) {
            assertThat(postBody(base()+"/presign", request, actor[0], actor[1]).getResponse().getStatus()).isEqualTo(403);
        }
        var upload = reserve("reserve", pdf, "invoice.pdf", DocumentPolicy.PDF);
        var complete = new HashMap<>(completeBody(upload,"cross",0,pdf));
        UUID other = UUID.randomUUID(); UUID revision = UUID.randomUUID(); seed(other,revision,"submitter");
        complete.put("draftRevisionId", revision);
        assertThat(postBody("/api/invoice-cases/"+other+"/documents/complete",complete,"submitter","SUBMITTER")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get(base()).with(user("submitter2").roles("SUBMITTER"))).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(count("document")).isZero();
        assertThat(mvc.perform(get(base())).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    @Test void wrongBytesTypeAndMissingObjectHaveNoDocumentOrVersionSideEffects() throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF);
        var complete = completeBody(upload,"complete",0,pdf);
        assertThat(postBody(base()+"/complete",complete,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(409);
        for (var value : List.of(new byte[]{1,2,3}, "%PDF-wronghash".getBytes())) {
            put(upload,value,DocumentPolicy.PDF);
            assertThat(postBody(base()+"/complete",complete,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(400);
        }
        put(upload,pdf,"text/plain");
        assertThat(postBody(base()+"/complete",complete,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(400);
        assertThat(count("document")).isZero();
        assertThat(jdbc.queryForObject("select version from invoice_case where id=?",Long.class,caseId)).isZero();
    }
    @Test void rejectsExpiredStaleAndSealedDraftBeforeRegistration() throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF); put(upload,pdf,DocumentPolicy.PDF);
        var complete = completeBody(upload,"complete",0,pdf);
        clock.now = clock.now.plusSeconds(601);
        assertThat(body(postBody(base()+"/complete",complete,"submitter","SUBMITTER")).get("code").asText()).isEqualTo("DOCUMENT_UPLOAD_EXPIRED");
        clock.now = Instant.now();
        jdbc.update("update invoice_case set version=1 where id=?",caseId);
        assertThat(body(postBody(base()+"/complete",complete,"submitter","SUBMITTER")).get("code").asText()).isEqualTo("STALE_CASE_VERSION");
        jdbc.update("update invoice_case set version=0 where id=?",caseId);
        jdbc.update("update draft_revision set status='SEALED',sealed_at=now() where id=?",draftId);
        assertThat(body(postBody(base()+"/complete",complete,"submitter","SUBMITTER")).get("code").asText()).isEqualTo("DRAFT_NOT_EDITABLE");
        assertThat(count("document")).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void concurrentCompletionRegistersOnce(boolean sameRequest) throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF); put(upload,pdf,DocumentPolicy.PDF);
        var bothRead = new CountDownLatch(2);
        afterRead.set(() -> {
            bothRead.countDown();
            try { assertThat(bothRead.await(10,TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> postBody(base()+"/complete",completeBody(upload,"one",0,pdf),"submitter","SUBMITTER"));
            var second = pool.submit(() -> postBody(base()+"/complete",completeBody(upload,sameRequest ? "one" : "two",0,pdf),"submitter","SUBMITTER"));
            var a = first.get(20,TimeUnit.SECONDS); var b = second.get(20,TimeUnit.SECONDS);
            assertThat(a.getResponse().getStatus()).isEqualTo(201); assertThat(b.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(a)).isEqualTo(body(b)); assertThat(count("document")).isEqualTo(1);
            assertThat(original(upload)).isEqualTo(pdf);
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentDraftChangeAfterStorageReadRejectsCommitAndCleansCandidateOriginal() throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF); put(upload,pdf,DocumentPolicy.PDF);
        afterRead.set(() -> jdbc.update("update invoice_case set version=1 where id=?",caseId));
        var result = postBody(base()+"/complete",completeBody(upload,"complete",0,pdf),"submitter","SUBMITTER");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("document")).isZero();
        var objects = CLIENT.listObjects(ListObjectsArgs.builder().bucket("invoice-documents").prefix("originals/"+caseId+"/").recursive(true).build());
        assertThat(objects.iterator().hasNext()).isFalse();
    }
    @Test void limitsReservationsAndValidatesMetadata() throws Exception {
        for (int i=0;i<10;i++) reserve("r-"+i,pdf,"invoice.pdf",DocumentPolicy.PDF);
        var excess = postBody(base()+"/presign",reserveBody("excess",pdf,"invoice.pdf",DocumentPolicy.PDF,0),"submitter","SUBMITTER");
        assertThat(body(excess).get("code").asText()).isEqualTo("DOCUMENT_LIMIT_REACHED");
        var invalid = new HashMap<>(reserveBody("invalid",pdf,"invoice.pdf",DocumentPolicy.PDF,0));
        invalid.put("sizeBytes",DocumentPolicy.MAX_BYTES+1);
        assertThat(postBody(base()+"/presign",invalid,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(400);
        invalid.put("sizeBytes",pdf.length); invalid.put("fileName","../invoice.pdf");
        assertThat(postBody(base()+"/presign",invalid,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(400);
    }
    @Test void acceptsXlsxSignatureAndProtectsDocumentRowsFromMutation() throws Exception {
        var buffer = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/workbook.xml"));
            zip.write("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheets/></workbook>".getBytes());
            zip.closeEntry();
        }
        byte[] xlsx = buffer.toByteArray();
        var upload = reserve("xlsx",xlsx,"invoice.xlsx",DocumentPolicy.XLSX); put(upload,xlsx,DocumentPolicy.XLSX);
        assertThat(postBody(base()+"/complete",completeBody(upload,"done",0,xlsx),"submitter","SUBMITTER")
                .getResponse().getStatus()).isEqualTo(201);
        assertThatThrownBy(() -> jdbc.update("update document set object_key='tampered'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from document")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint()+"/invoice-documents"))
                .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(403);
    }
    @Test void auditFailureRollsBackDocumentVersionAndIdempotencyThenAllowsSameRequestRetry() throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF); put(upload,pdf,DocumentPolicy.PDF);
        var request = completeBody(upload,"complete",0,pdf);
        jdbc.execute("alter table audit_entry add constraint test_document_audit_failure check (action <> 'DOCUMENT_REGISTERED')");
        try {
            assertThat(postBody(base()+"/complete",request,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(409);
            assertThat(count("document")).isZero();
            assertThat(jdbc.queryForObject("select version from invoice_case where id=?",Long.class,caseId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='document:complete'",Long.class)).isZero();
            assertThat(CLIENT.listObjects(ListObjectsArgs.builder().bucket("invoice-documents")
                    .prefix("originals/"+caseId+"/").recursive(true).build()).iterator().hasNext()).isFalse();
        } finally { jdbc.execute("alter table audit_entry drop constraint test_document_audit_failure"); }
        assertThat(postBody(base()+"/complete",request,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(201);
    }
    @Test void duplicateCompletionPayloadConflictAndSignedPathTamperingDoNotChangeOriginal() throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF); put(upload,pdf,DocumentPolicy.PDF);
        var request = completeBody(upload,"complete",0,pdf);
        assertThat(postBody(base()+"/complete",request,"submitter","SUBMITTER").getResponse().getStatus()).isEqualTo(201);
        var altered = new HashMap<>(request); altered.put("checksum","0".repeat(64));
        assertThat(body(postBody(base()+"/complete",altered,"submitter","SUBMITTER")).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        altered.put("requestId","different");
        assertThat(body(postBody(base()+"/complete",altered,"submitter","SUBMITTER")).get("code").asText()).isEqualTo("DOCUMENT_SUBJECT_CONFLICT");
        String url = upload.get("uploadUrl").asText().replace("/uploads/","/originals/");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(pdf)).build(),HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(original(upload)).isEqualTo(pdf);
    }
    @Test void browserPutPreflightIsAcceptedAndOriginalDownloadRemainsPrivate() throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF);
        var preflight = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(upload.get("uploadUrl").asText()))
                .timeout(Duration.ofSeconds(10)).header("Origin","http://localhost:3000")
                .header("Access-Control-Request-Method","PUT").header("Access-Control-Request-Headers","content-type")
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
        assertThat(preflight.statusCode()).isEqualTo(204);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin").orElse("")).isIn("*","http://localhost:3000");
        put(upload,pdf,DocumentPolicy.PDF);
        assertThat(postBody(base()+"/complete",completeBody(upload,"done",0,pdf),"submitter","SUBMITTER")
                .getResponse().getStatus()).isEqualTo(201);
        String key = jdbc.queryForObject("select object_key from document where id=?",String.class,
                UUID.fromString(upload.get("documentId").asText()));
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint()+"/invoice-documents/"+key))
                .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertThat(anonymous.statusCode()).isEqualTo(403);
    }
    @Test void rejectsActualOversizedObjectAndSignatureMismatchWithCorrectChecksum() throws Exception {
        var upload = reserve("reserve",pdf,"invoice.pdf",DocumentPolicy.PDF);
        var oversized = new byte[DocumentPolicy.MAX_BYTES+1];
        put(upload,oversized,DocumentPolicy.PDF);
        assertThat(postBody(base()+"/complete",completeBody(upload,"large",0,pdf),"submitter","SUBMITTER")
                .getResponse().getStatus()).isEqualTo(400);
        byte[] invalid = "not-a-pdf".getBytes();
        var bad = reserve("signature",invalid,"invoice.pdf",DocumentPolicy.PDF); put(bad,invalid,DocumentPolicy.PDF);
        assertThat(postBody(base()+"/complete",completeBody(bad,"bad",0,invalid),"submitter","SUBMITTER")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(count("document")).isZero();
    }
}
