package com.invoicematch.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.infrastructure.MinioDocumentStorage;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.MinioTestSupport;
import io.minio.MinioClient;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

/**
 * P2-03 authorized original access against real PostgreSQL and MinIO: case-read
 * roles and ownership, registered-only lookup, attachment/inline disposition,
 * signed response headers and 120-second expiry, tamper rejection, no-store, and
 * the absence of any original read/write or business side effect while issuing
 * the URL.
 */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class DocumentDownloadIntegrationTest extends AbstractPostgresIntegrationTest {

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
        r.add("document-storage.endpoint", DocumentDownloadIntegrationTest::endpoint);
        r.add("document-storage.public-endpoint", DocumentDownloadIntegrationTest::endpoint);
        r.add("document-storage.access-key", () -> ACCESS);
        r.add("document-storage.secret-key", () -> SECRET);
    }
    @AfterAll static void stopStorage() { MINIO.stop(); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean MinioDocumentStorage storage;

    final byte[] pdf = "%PDF-1.7\np2-03-fixture".getBytes(StandardCharsets.UTF_8);
    UUID caseId;
    UUID draftId;

    @BeforeEach void setup() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        caseId = UUID.randomUUID();
        draftId = UUID.randomUUID();
        seed(caseId, draftId, "owner");
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return i.callRealMethod();
        }).when(storage).presignDownload(any());
    }

    // ------------------------------------------------------------------
    // Happy path: default attachment, trusted type, 120 seconds, no-store
    // ------------------------------------------------------------------

    @Test void defaultAttachmentIssuesSignedUrlWithTrustedHeadersAndNoBusinessSideEffects() throws Exception {
        var document = complete(caseId, draftId, "doc", "invoice.pdf", DocumentPolicy.PDF, pdf);
        UUID documentId = UUID.fromString(document.get("documentId").asText());
        long versionBefore = version(caseId);
        byte[] originalBefore = original(documentId);
        Map<String, Object> bundleBefore = bundleState(caseId);

        var result = mvc.perform(get(base() + "/" + documentId + "/download-url")
                .with(user("owner").roles("SUBMITTER"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode view = body(result);
        assertThat(fieldNames(view)).containsExactlyInAnyOrder(
                "documentId", "fileName", "mediaType", "url", "method", "expiresAt");
        assertThat(view.get("documentId").asText()).isEqualTo(documentId.toString());
        assertThat(view.get("fileName").asText()).isEqualTo("invoice.pdf");
        assertThat(view.get("mediaType").asText()).isEqualTo(DocumentPolicy.PDF);
        assertThat(view.get("method").asText()).isEqualTo("GET");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(SECRET).doesNotContain("objectKey");

        String url = view.get("url").asText();
        // The DTO expiry must be exactly the signed URL's own signing date plus
        // its lifetime; presigned URLs carry whole-second X-Amz-Date precision.
        assertThat(Instant.parse(view.get("expiresAt").asText())).isEqualTo(signedExpiry(url));
        assertThat(queryParams(url)).containsEntry("X-Amz-Expires", "120");
        assertThat(queryParams(url)).containsEntry("response-content-type", DocumentPolicy.PDF);
        assertThat(queryParams(url).get("response-content-disposition")).startsWith("attachment;");
        HttpResponse<byte[]> fetched = fetch(url);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.headers().firstValue("Content-Type").orElse("")).isEqualTo(DocumentPolicy.PDF);
        assertThat(fetched.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");
        assertThat(fetched.body()).isEqualTo(pdf);

        assertThat(version(caseId)).isEqualTo(versionBefore);
        assertThat(documentCount()).isEqualTo(1);
        assertThat(referenceCount()).isEqualTo(1);
        assertThat(original(documentId)).isEqualTo(originalBefore);
        assertThat(bundleState(caseId)).isEqualTo(bundleBefore);
        assertThat(auditCount("DOCUMENT_REGISTERED")).isEqualTo(1);
        assertThat(evidenceBundleCount()).isZero();
    }

    @Test void inlinePdfWithUnicodeAndQuotedNameReturnsSafeInlineHeader() throws Exception {
        String name = "청구 \"서\" (v1).pdf";
        var document = complete(caseId, draftId, "doc", name, DocumentPolicy.PDF, pdf);
        UUID documentId = UUID.fromString(document.get("documentId").asText());

        var result = mvc.perform(get(base() + "/" + documentId + "/download-url")
                .param("disposition", "inline").with(user("owner").roles("SUBMITTER"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        HttpResponse<byte[]> fetched = fetch(body(result).get("url").asText());
        assertThat(fetched.statusCode()).isEqualTo(200);
        String disposition = fetched.headers().firstValue("Content-Disposition").orElse("");
        assertThat(disposition).startsWith("inline;");
        assertThat(disposition).contains("filename*=UTF-8''").doesNotContain("\r", "\n");
        assertThat(disposition).contains("filename=\"__ ___ (v1).pdf\"");
        assertThat(fetched.headers().firstValue("Content-Type").orElse("")).isEqualTo(DocumentPolicy.PDF);
        assertThat(fetched.body()).isEqualTo(pdf);
    }

    @Test void xlsxDownloadsAsAttachmentButInlineAndUnknownDispositionAreRejected() throws Exception {
        byte[] xlsx = xlsx();
        var document = complete(caseId, draftId, "doc", "invoice.xlsx", DocumentPolicy.XLSX, xlsx);
        UUID documentId = UUID.fromString(document.get("documentId").asText());

        var attachment = mvc.perform(get(base() + "/" + documentId + "/download-url")
                .with(user("owner").roles("SUBMITTER"))).andReturn();
        assertThat(attachment.getResponse().getStatus()).isEqualTo(200);
        HttpResponse<byte[]> fetched = fetch(body(attachment).get("url").asText());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");
        assertThat(fetched.headers().firstValue("Content-Type").orElse("")).isEqualTo(DocumentPolicy.XLSX);
        assertThat(fetched.body()).isEqualTo(xlsx);

        var inline = mvc.perform(get(base() + "/" + documentId + "/download-url")
                .param("disposition", "inline").with(user("owner").roles("SUBMITTER"))).andReturn();
        assertThat(inline.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(inline).get("code").asText()).isEqualTo("INVALID_DISPOSITION");

        var bogus = mvc.perform(get(base() + "/" + documentId + "/download-url")
                .param("disposition", "popup").with(user("owner").roles("SUBMITTER"))).andReturn();
        assertThat(bogus.getResponse().getStatus()).isEqualTo(400);

        // Only an absent parameter defaults to attachment; an explicitly empty
        // or whitespace value is a 400, not a silent default.
        for (String blank : List.of("", " ")) {
            var present = mvc.perform(get(base() + "/" + documentId + "/download-url")
                    .param("disposition", blank).with(user("owner").roles("SUBMITTER"))).andReturn();
            assertThat(present.getResponse().getStatus()).as("disposition=[" + blank + "]").isEqualTo(400);
        }
    }

    // ------------------------------------------------------------------
    // Authorization, cross-case and incomplete reservation
    // ------------------------------------------------------------------

    @Test void caseReadRolesWorkAndOtherSubmitterCrossCaseAndIncompleteAreDenied() throws Exception {
        var document = complete(caseId, draftId, "doc", "invoice.pdf", DocumentPolicy.PDF, pdf);
        UUID documentId = UUID.fromString(document.get("documentId").asText());
        String path = base() + "/" + documentId + "/download-url";

        assertThat(mvc.perform(get(path).with(user("approver").roles("APPROVER"))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get(path).with(user("operator").roles("OPERATOR"))).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get(path).with(user("other").roles("SUBMITTER"))).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(401);

        UUID otherCase = UUID.randomUUID();
        UUID otherDraft = UUID.randomUUID();
        seed(otherCase, otherDraft, "other");
        assertThat(mvc.perform(get("/api/invoice-cases/" + otherCase + "/documents/" + documentId + "/download-url")
                .with(user("approver").roles("APPROVER"))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/invoice-cases/" + caseId + "/documents/" + UUID.randomUUID() + "/download-url")
                .with(user("approver").roles("APPROVER"))).andReturn().getResponse().getStatus()).isEqualTo(404);

        JsonNode pending = reserve(caseId, draftId, "pending", "pending.pdf", DocumentPolicy.PDF, pdf);
        assertThat(mvc.perform(get(base() + "/" + pending.get("documentId").asText() + "/download-url")
                .with(user("owner").roles("SUBMITTER"))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Tampering and private object access
    // ------------------------------------------------------------------

    @Test void tamperedPathOrDispositionAndUnsignedObjectAreRejectedWithoutChangingOriginal() throws Exception {
        var document = complete(caseId, draftId, "doc", "invoice.pdf", DocumentPolicy.PDF, pdf);
        UUID documentId = UUID.fromString(document.get("documentId").asText());
        byte[] originalBefore = original(documentId);

        String url = body(mvc.perform(get(base() + "/" + documentId + "/download-url")
                .with(user("owner").roles("SUBMITTER"))).andReturn()).get("url").asText();
        String tamperedPath = url.replace("/originals/", "/uploads/");
        assertThat(fetch(tamperedPath).statusCode()).isEqualTo(403);
        String tamperedDisposition = url.replace("response-content-disposition=attachment",
                "response-content-disposition=inline");
        assertThat(fetch(tamperedDisposition).statusCode()).isEqualTo(403);

        String key = jdbc.queryForObject("select object_key from document where id = ?", String.class, documentId);
        assertThat(fetch(endpoint() + "/invoice-documents/" + key).statusCode()).isEqualTo(403);
        assertThat(original(documentId)).isEqualTo(originalBefore);
    }

    // ------------------------------------------------------------------
    // Issuing the URL touches no original bytes, DB write or transaction
    // ------------------------------------------------------------------

    @Test void issuingUrlRunsOutsideTransactionAndNeverReadsWritesOrRemovesOriginals() throws Exception {
        var document = complete(caseId, draftId, "doc", "invoice.pdf", DocumentPolicy.PDF, pdf);
        UUID documentId = UUID.fromString(document.get("documentId").asText());
        clearInvocations(storage);

        var result = mvc.perform(get(base() + "/" + documentId + "/download-url")
                .with(user("owner").roles("SUBMITTER"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        verify(storage, never()).readVerified(any());
        verify(storage, never()).writeOriginal(any(), any(), any());
        verify(storage, never()).removeOriginal(any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    String base() { return "/api/invoice-cases/" + caseId + "/documents"; }

    void seed(UUID id, UUID revision, String owner) {
        jdbc.update("insert into invoice_case (id,supplier_id,purchase_order_id,invoice_number,normalized_invoice_number,"
                + "submitted_by,status,version,created_at,updated_at) values (?,'SUP-1','PO-1001',?,? ,?,'DRAFT',0,now(),now())",
                id, id.toString(), id.toString(), owner);
        jdbc.update("insert into draft_revision (id,invoice_case_id,revision_number,status,created_at) values (?, ?,1,'OPEN',now())",
                revision, id);
        jdbc.update("update invoice_case set current_draft_revision_id = ? where id = ?", revision, id);
    }

    JsonNode reserve(UUID caseId, UUID draftId, String requestId, String name, String mediaType, byte[] bytes)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", version(caseId));
        body.put("draftRevisionId", draftId);
        body.put("fileName", name);
        body.put("mediaType", mediaType);
        body.put("sizeBytes", (long) bytes.length);
        body.put("checksum", DocumentPolicy.hash(bytes));
        MvcResult result = mvc.perform(post("/api/invoice-cases/" + caseId + "/documents/presign")
                .with(user("owner").roles("SUBMITTER")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    JsonNode complete(UUID caseId, UUID draftId, String requestId, String name, String mediaType, byte[] bytes)
            throws Exception {
        JsonNode upload = reserve(caseId, draftId, "reserve-" + requestId, name, mediaType, bytes);
        var put = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(upload.get("uploadUrl").asText()))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", mediaType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
        Map<String, Object> body = Map.of("requestId", requestId, "expectedCaseVersion", version(caseId),
                "draftRevisionId", draftId, "documentId", upload.get("documentId").asText(),
                "checksum", DocumentPolicy.hash(bytes));
        MvcResult result = mvc.perform(post("/api/invoice-cases/" + caseId + "/documents/complete")
                .with(user("owner").roles("SUBMITTER")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    HttpResponse<byte[]> fetch(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    byte[] original(UUID documentId) throws Exception {
        String key = jdbc.queryForObject("select object_key from document where id = ?", String.class, documentId);
        try (var object = CLIENT.getObject(io.minio.GetObjectArgs.builder().bucket("invoice-documents").object(key).build())) {
            return object.readAllBytes();
        }
    }

    long version(UUID caseId) { return jdbc.queryForObject("select version from invoice_case where id = ?", Long.class, caseId); }
    long documentCount() { return jdbc.queryForObject("select count(*) from document", Long.class); }
    long referenceCount() { return jdbc.queryForObject("select count(*) from draft_revision_document", Long.class); }
    long evidenceBundleCount() { return jdbc.queryForObject("select count(*) from evidence_bundle", Long.class); }
    long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_entry where action = ?", Long.class, action);
    }

    Map<String, Object> bundleState(UUID caseId) {
        return jdbc.queryForMap("select payload::text as payload, payload_hash from evidence_bundle where invoice_case_id = ?"
                + " union all select '', '' limit 1", caseId);
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static Map<String, String> queryParams(String url) throws Exception {
        Map<String, String> params = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    static Instant signedExpiry(String url) throws Exception {
        Map<String, String> params = queryParams(url);
        return Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                .parse(params.get("X-Amz-Date"))).plusSeconds(Long.parseLong(params.get("X-Amz-Expires")));
    }

    static byte[] xlsx() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheets/></workbook>".getBytes());
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
