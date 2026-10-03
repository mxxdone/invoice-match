package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.invoicematch.core.analysis.application.ClaimOutcome;
import com.invoicematch.core.document.application.DocumentOriginalRequest;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.infrastructure.MinioDocumentStorage;
import com.invoicematch.core.support.MinioTestSupport;
import io.minio.MinioClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class AnalysisSourceIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    static final String ACCESS = "test-" + UUID.randomUUID();
    static final String SECRET = UUID.randomUUID().toString();
    static final GenericContainer<?> MINIO = new GenericContainer<>("invoice-match-minio:p2-security-2025-10-15")
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            .withStartupTimeout(Duration.ofSeconds(60));
    static {
        MINIO.start();
        try {
            MinioTestSupport.initializeBucket(MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET)
                    .region("us-east-1").build(), "invoice-documents");
        } catch (RuntimeException e) { MINIO.stop(); throw e; }
    }
    static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    @DynamicPropertySource static void storageProperties(DynamicPropertyRegistry r) {
        r.add("document-storage.enabled", () -> true);
        r.add("document-storage.endpoint", AnalysisSourceIntegrationTest::endpoint);
        r.add("document-storage.public-endpoint", AnalysisSourceIntegrationTest::endpoint);
        r.add("document-storage.access-key", () -> ACCESS);
        r.add("document-storage.secret-key", () -> SECRET);
        r.add("analysis.execution.lease-duration", () -> "10s");
    }
    @AfterAll static void stopStorage() { MINIO.stop(); }
    @Autowired MockMvc mvc;
    @MockitoSpyBean MinioDocumentStorage storage;
    final byte[] bytes = "%PDF-1.7\noriginal".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private RunFixture sourceRun(boolean upload) {
        UUID caseId = createDraftCase("INV-SOURCE-" + UUID.randomUUID());
        var document = seedDocument(caseId, UUID.randomUUID(), "source.pdf", DocumentPolicy.PDF,
                bytes.length, DocumentPolicy.hash(bytes));
        if (upload) storage.writeOriginal(key(caseId, document.documentId()), bytes, DocumentPolicy.PDF);
        submit(caseId, "source-submit-" + caseId);
        return fixture(caseId, java.util.List.of(document));
    }
    private String key(UUID caseId, UUID documentId) { return "originals/" + caseId + "/" + documentId; }
    private MvcResult read(RunFixture run, UUID doc, UUID token, String bearer) throws Exception {
        return mvc.perform(post("/internal/analysis-runs/{id}/documents/{doc}/source", run.runId(), doc)
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("claimToken", token, "inputVersion", run.inputVersion(),
                        "evidencePayloadHash", run.evidencePayloadHash())))).andReturn();
    }
    private MvcResult read(RunFixture run, UUID doc, UUID token) throws Exception {
        return read(run, doc, token, "Bearer " + WORKER_TOKEN);
    }

    @Test void verifiedSourceReturnsBytesWithoutStorageTransaction() throws Exception {
        var run = sourceRun(true);
        var claim = claim(run);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(storage).readOriginal(any());
        var response = read(run, run.documents().getFirst().documentId(), claim.claimToken()).getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(response.getContentType()).isEqualTo(DocumentPolicy.PDF);
        assertThat(response.getHeader("Content-Length")).isEqualTo(Integer.toString(bytes.length));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test void otherCaseDocumentAndWrongClaimNeverReachStorage() throws Exception {
        var run = sourceRun(true);
        var other = sourceRun(true);
        var claim = claim(run);
        clearInvocations(storage);
        assertThat(read(run, other.documents().getFirst().documentId(), claim.claimToken()).getResponse().getStatus()).isEqualTo(409);
        assertThat(read(run, run.documents().getFirst().documentId(), UUID.randomUUID()).getResponse().getStatus()).isEqualTo(409);
        verify(storage, never()).readOriginal(any());
    }

    @Test void machineSecretAndActiveExecutionAreBothRequired() throws Exception {
        var run = sourceRun(true);
        var doc = run.documents().getFirst().documentId();
        assertThat(read(run, doc, UUID.randomUUID(), "Bearer invalid").getResponse().getStatus()).isEqualTo(401);
        assertThat(read(run, doc, UUID.randomUUID()).getResponse().getStatus()).isEqualTo(409);
        var claim = claim(run);
        assertThat(read(run, doc, claim.claimToken(), "Basic c3VibWl0dGVyOnN1Ym1pdHRlcg==").getResponse().getStatus()).isEqualTo(401);
    }

    @Test void missingOrCorruptedOriginalIsFixed503() throws Exception {
        var run = sourceRun(false);
        var claim = claim(run);
        var doc = run.documents().getFirst().documentId();
        for (int i = 0; i < 2; i++) {
            var response = read(run, doc, claim.claimToken()).getResponse();
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getContentAsString()).contains("SOURCE_UNAVAILABLE")
                    .doesNotContain("originals/", SECRET, "NoSuchKey", endpoint());
            storage.writeOriginal(key(run.caseId(), doc), "%PDF-damaged".getBytes(), DocumentPolicy.PDF);
        }
    }

    @Test void sourceReadCannotReturnAfterRunBecomesStale() throws Exception {
        var run = sourceRun(true);
        var claim = claim(run);
        doAnswer(invocation -> {
            var source = invocation.callRealMethod();
            jdbc.update("update analysis_run set status='STALE', execution_token=null, lease_until=null where id=?", run.runId());
            return source;
        }).when(storage).readOriginal(any());
        var response = read(run, run.documents().getFirst().documentId(), claim.claimToken()).getResponse();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsByteArray()).isNotEqualTo(bytes);
    }

    @Test void expiredLeaseCannotReadOriginal() throws Exception {
        var run = sourceRun(true);
        var claim = claim(run);
        Thread.sleep(10_100);
        clearInvocations(storage);
        assertThat(read(run, run.documents().getFirst().documentId(), claim.claimToken()).getResponse().getStatus()).isEqualTo(409);
        verify(storage, never()).readOriginal(any());
    }
}
