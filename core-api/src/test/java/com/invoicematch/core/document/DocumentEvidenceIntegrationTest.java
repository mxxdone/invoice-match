package com.invoicematch.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.document.application.DocumentPolicy;
import com.invoicematch.core.document.infrastructure.MinioDocumentStorage;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceLineRepository;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
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
 * P2-02 document evidence against real PostgreSQL and MinIO: completed documents
 * are frozen into the submitted bundle, supplement revisions inherit prior
 * document references without copying files, the reservation limit counts
 * inherited and active reservations once, and the database rejects forged
 * references or legacy downgrades. Approval reconstructs the authoritative
 * document metadata, and a historical legitimate legacy bundle with registered
 * documents still approves without retroactively gaining them.
 */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import(DocumentEvidenceIntegrationTest.TimeConfig.class)
class DocumentEvidenceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final String ITEM_A = "ITEM-A4-80";

    static final String ACCESS = "test-" + UUID.randomUUID();
    static final String SECRET = UUID.randomUUID().toString();
    static final GenericContainer<?> MINIO = new GenericContainer<>("invoice-match-minio:p2-security-2025-10-15")
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)).withStartupTimeout(Duration.ofSeconds(60));
    static final MinioClient CLIENT;
    static final StubPurchasingServer STUB;

    static {
        try {
            STUB = new StubPurchasingServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        MINIO.start();
        CLIENT = MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).region("us-east-1").build();
        try {
            CLIENT.makeBucket(MakeBucketArgs.builder().bucket("invoice-documents").build());
        } catch (Exception e) {
            MINIO.stop();
            STUB.close();
            throw new ExceptionInInitializerError(e);
        }
    }

    static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("document-storage.enabled", () -> true);
        r.add("document-storage.endpoint", DocumentEvidenceIntegrationTest::endpoint);
        r.add("document-storage.public-endpoint", DocumentEvidenceIntegrationTest::endpoint);
        r.add("document-storage.access-key", () -> ACCESS);
        r.add("document-storage.secret-key", () -> SECRET);
        r.add("purchasing-system.base-url", STUB::baseUrl);
        r.add("purchasing-system.connect-timeout", () -> "1s");
        r.add("purchasing-system.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stopStorage() {
        MINIO.stop();
        STUB.close();
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock documentEvidenceClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    EvidenceBundlePayloadHasher bundleHasher;
    @Autowired
    InvoiceCaseRepository invoiceCases;
    @Autowired
    InvoiceLineRepository invoiceLines;
    @MockitoSpyBean
    MinioDocumentStorage storage;

    final AtomicReference<Runnable> afterRead = new AtomicReference<>(() -> {});
    final byte[] pdf = "%PDF-1.7\np2-02-fixture".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        clock.now = Instant.now();
        afterRead.set(() -> {});
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var bytes = i.callRealMethod();
            afterRead.get().run();
            return bytes;
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

    // ------------------------------------------------------------------
    // Submission freezes completed documents, pending reservations do not
    // ------------------------------------------------------------------

    @Test
    void submitFreezesCompletedDocumentMetadataWithSchemaTwoAndPendingReservationIgnored() throws Exception {
        JsonNode created = createCase("c1", "INV-1");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d1", created.get("version").asLong(), 1, 60, 2500, ITEM_A);

        JsonNode completed = completeDocument(caseId, revision, "doc-a", currentCaseVersion(caseId), pdf, "a.pdf",
                DocumentPolicy.PDF);
        // A reservation that never completes must not enter the bundle and must
        // not block submission.
        reserveDocument(caseId, revision, "pending", currentCaseVersion(caseId), "pending.pdf", DocumentPolicy.PDF);

        MvcResult submitted = submit(caseId, "s1", currentCaseVersion(caseId));
        assertThat(submitted.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> bundle = jdbc.queryForMap(
                "select payload_schema, payload::text as payload, payload_hash from evidence_bundle"
                        + " where invoice_case_id = ? and version_number = 1",
                caseId);
        assertThat(bundle.get("payload_schema")).isEqualTo("document-v2");
        String payload = (String) bundle.get("payload");
        assertThat(payload).contains("\"schemaVersion\": 2");
        assertThat(payload).contains(completed.get("documentId").asText());
        assertThat(payload).contains(revision.toString());
        assertThat(payload).contains("a.pdf");
        assertThat(payload).contains(DocumentPolicy.hash(pdf));
        assertThat(payload).doesNotContain("pending.pdf");
        assertThat(payload).doesNotContain("originals/", "uploads/", "X-Amz");
        assertThat(jdbc.queryForObject("select count(*) from draft_revision_document where draft_revision_id = ?",
                Long.class, revision)).isEqualTo(1L);
    }

    @Test
    void submitWithoutCompletedDocumentsKeepsTheFrozenLegacyPayload() throws Exception {
        JsonNode created = createCase("c2", "INV-2");
        UUID caseId = UUID.fromString(created.get("id").asText());
        replaceDraft(caseId, "d2", created.get("version").asLong(), 1, 60, 2500, ITEM_A);

        InvoiceCase invoiceCase = invoiceCases.findById(caseId).orElseThrow();
        List<InvoiceLine> lines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(
                UUID.fromString(created.get("currentRevision").get("id").asText()));
        EvidenceBundlePayloadHasher.CanonicalPayload expected =
                bundleHasher.canonicalize(invoiceCase, 1, lines);

        assertThat(submit(caseId, "s2", currentCaseVersion(caseId)).getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> bundle = jdbc.queryForMap(
                "select payload_schema, payload::text as payload, payload_hash, payload = cast(? as jsonb) as same_json"
                        + " from evidence_bundle where invoice_case_id = ?",
                expected.json(), caseId);
        assertThat(bundle.get("payload_schema")).isEqualTo("legacy-v1");
        assertThat((String) bundle.get("payload_hash")).isEqualTo(expected.hash());
        assertThat(bundle.get("same_json")).isEqualTo(true);
        assertThat((String) bundle.get("payload")).doesNotContain("schemaVersion", "documents");
    }

    // ------------------------------------------------------------------
    // Supplement inheritance and capacity
    // ------------------------------------------------------------------

    @Test
    void supplementInheritsPriorDocumentIdsAddsNewDocumentsAndKeepsPastBundleUntouched() throws Exception {
        JsonNode created = createCase("c3", "INV-3");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision1 = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d3", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        JsonNode first = completeDocument(caseId, revision1, "doc-1", currentCaseVersion(caseId), pdf, "first.pdf",
                DocumentPolicy.PDF);
        submit(caseId, "s3", currentCaseVersion(caseId));

        Map<String, Object> v1Before = jdbc.queryForMap(
                "select payload::text as payload, payload_hash from evidence_bundle where invoice_case_id = ?",
                caseId);
        byte[] originalBefore = originalBytes(UUID.fromString(first.get("documentId").asText()));

        runMatch(caseId, "m3");
        JsonNode snapshot = freeze(caseId, "f3");
        requestSupplement(caseId, "sup3", snapshot.get("id").asText(), snapshot.get("payloadHash").asText());

        JsonNode revised = openRevision(caseId, "r3");
        UUID revision2 = UUID.fromString(revised.get("currentRevision").get("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from draft_revision_document where draft_revision_id = ?",
                Long.class, revision2)).isEqualTo(1L);

        JsonNode second = completeDocument(caseId, revision2, "doc-2", currentCaseVersion(caseId),
                "%PDF-1.7\nsecond".getBytes(StandardCharsets.UTF_8), "second.pdf", DocumentPolicy.PDF);
        assertThat(submit(caseId, "s3b", currentCaseVersion(caseId)).getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> v2 = jdbc.queryForMap(
                "select payload_schema, payload::text as payload from evidence_bundle"
                        + " where invoice_case_id = ? and version_number = 2",
                caseId);
        assertThat(v2.get("payload_schema")).isEqualTo("document-v2");
        String payload = (String) v2.get("payload");
        List<String> documentIds = new ArrayList<>();
        json.readTree(payload).get("documents").forEach(node -> documentIds.add(node.get("documentId").asText()));
        assertThat(documentIds).containsExactlyInAnyOrder(
                first.get("documentId").asText(), second.get("documentId").asText());
        List<String> sortedIds = new ArrayList<>(documentIds);
        Collections.sort(sortedIds);
        assertThat(documentIds).isEqualTo(sortedIds);
        // The prior bundle, its hash and the original bytes are untouched.
        assertThat(jdbc.queryForMap(
                        "select payload::text as payload, payload_hash from evidence_bundle where invoice_case_id = ?"
                                + " and version_number = 1",
                        caseId))
                .isEqualTo(v1Before);
        assertThat(originalBytes(UUID.fromString(first.get("documentId").asText()))).isEqualTo(originalBefore);
    }

    @Test
    void inheritedDocumentsCountTowardTheRevisionLimitWithoutDoubleCounting() throws Exception {
        JsonNode created = createCase("c4", "INV-4");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision1 = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d4", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        completeDocument(caseId, revision1, "doc-1", currentCaseVersion(caseId), pdf, "first.pdf", DocumentPolicy.PDF);
        submit(caseId, "s4", currentCaseVersion(caseId));
        runMatch(caseId, "m4");
        JsonNode snapshot = freeze(caseId, "f4");
        requestSupplement(caseId, "sup4", snapshot.get("id").asText(), snapshot.get("payloadHash").asText());
        JsonNode revised = openRevision(caseId, "r4");
        UUID revision2 = UUID.fromString(revised.get("currentRevision").get("id").asText());

        // Nine more active reservations plus the one inherited reference make ten.
        for (int i = 0; i < 9; i++) {
            reserveDocument(caseId, revision2, "res-" + i, currentCaseVersion(caseId), "r" + i + ".pdf",
                    DocumentPolicy.PDF);
        }
        MvcResult excess = perform(post("/api/invoice-cases/{id}/documents/presign", caseId)
                        .with(user("submitter").roles("SUBMITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(reserveBody(caseId, revision2, "over",
                                currentCaseVersion(caseId), "over.pdf", DocumentPolicy.PDF))))
                ;
        assertThat(json.readTree(excess.getResponse().getContentAsByteArray()).get("code").asText())
                .isEqualTo("DOCUMENT_LIMIT_REACHED");
    }

    // ------------------------------------------------------------------
    // Database guards
    // ------------------------------------------------------------------

    @Test
    void databaseRejectsCrossCaseSealedReferenceAndReferenceMutation() throws Exception {
        JsonNode created = createCase("c5", "INV-5");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d5", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        JsonNode document = completeDocument(caseId, revision, "doc-5", currentCaseVersion(caseId), pdf, "a.pdf",
                DocumentPolicy.PDF);
        UUID documentId = UUID.fromString(document.get("documentId").asText());

        // Cross-case reference: the document's immutable upload belongs to the
        // other case, so the composite foreign key rejects it.
        JsonNode other = createCase("c5-other", "INV-5B");
        UUID otherCaseId = UUID.fromString(other.get("id").asText());
        UUID otherRevision = UUID.fromString(other.get("currentRevision").get("id").asText());
        assertThatThrownBy(() -> jdbc.update(
                        "insert into draft_revision_document (draft_revision_id, document_id, invoice_case_id,"
                                + " created_at) values (?, ?, ?, now())",
                        otherRevision, documentId, otherCaseId))
                .isInstanceOf(DataAccessException.class);

        // A sealed revision cannot accept a new reference. Register a stray
        // document while the other case's revision is open through raw SQL
        // (no reference), then seal that revision and attempt the insert.
        UUID strayDocument = UUID.randomUUID();
        jdbc.update("insert into document_upload (id, invoice_case_id, draft_revision_id, file_name, media_type,"
                + " size_bytes, checksum, upload_key, expires_at, created_at)"
                + " values (?, ?, ?, 'stray.pdf', ?, 4, ?, ?, now() + interval '10 minutes', now())",
                strayDocument, otherCaseId, otherRevision, DocumentPolicy.PDF, DocumentPolicy.hash(pdf),
                "uploads/stray/" + strayDocument);
        jdbc.update("insert into document (id, object_key, registered_case_version, registered_at)"
                + " values (?, ?, 0, now())", strayDocument, "originals/stray/" + strayDocument);
        jdbc.update("update draft_revision set status = 'SEALED', sealed_at = now() where id = ?", otherRevision);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into draft_revision_document (draft_revision_id, document_id, invoice_case_id,"
                                + " created_at) values (?, ?, ?, now())",
                        otherRevision, strayDocument, otherCaseId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("current open draft");

        // Existing references are append-only.
        assertThatThrownBy(() -> jdbc.update(
                        "update draft_revision_document set document_id = ? where document_id = ?",
                        UUID.randomUUID(), documentId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from draft_revision_document where document_id = ?", documentId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsLegacyDowngradeOfADocumentBearingSealedRevision() throws Exception {
        JsonNode created = createCase("c6", "INV-6");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d6", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        completeDocument(caseId, revision, "doc-6", currentCaseVersion(caseId), pdf, "a.pdf", DocumentPolicy.PDF);
        submit(caseId, "s6", currentCaseVersion(caseId));

        // Explicit legacy schema and the default both attempt a downgrade.
        assertThatThrownBy(() -> jdbc.update(
                        "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                                + " payload_schema, payload_hash, payload, submitted_at)"
                                + " values (?, ?, ?, 2, 'legacy-v1', 'h', '{}'::jsonb, now())",
                        UUID.randomUUID(), caseId, revision))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("document-v2");
        assertThatThrownBy(() -> jdbc.update(
                        "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                                + " payload_hash, payload, submitted_at)"
                                + " values (?, ?, ?, 2, 'h', '{}'::jsonb, now())",
                        UUID.randomUUID(), caseId, revision))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("document-v2");
        // Unknown persisted schemas are rejected by the check constraint.
        assertThatThrownBy(() -> jdbc.update(
                        "insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                                + " payload_schema, payload_hash, payload, submitted_at)"
                                + " values (?, ?, ?, 2, 'bogus', 'h', '{}'::jsonb, now())",
                        UUID.randomUUID(), caseId, revision))
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------
    // Submission concurrency, rollback and idempotency
    // ------------------------------------------------------------------

    @Test
    void submitCommittingFirstMakesTheLateCompletionFailAndCleansTheCandidateObject() throws Exception {
        JsonNode created = createCase("c7", "INV-7");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d7", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        JsonNode upload = reserveDocument(caseId, revision, "res-7", currentCaseVersion(caseId), "late.pdf",
                DocumentPolicy.PDF);
        putBytes(upload, pdf, DocumentPolicy.PDF);

        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        afterRead.set(() -> {
            blocked.countDown();
            try {
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var completion = pool.submit(() -> complete(caseId, upload, "doc-7", currentCaseVersion(caseId), pdf));
            assertThat(blocked.await(20, TimeUnit.SECONDS)).isTrue();
            // Submission seals the draft while the completion is still reading
            // storage, so the late completion loses.
            assertThat(submit(caseId, "s7", currentCaseVersion(caseId)).getResponse().getStatus()).isEqualTo(200);
            release.countDown();
            MvcResult late = completion.get(20, TimeUnit.SECONDS);
            assertThat(late.getResponse().getStatus()).isEqualTo(409);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from document where id = ?",
                Long.class, UUID.fromString(upload.get("documentId").asText()))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from draft_revision_document", Long.class)).isZero();
        assertThat(CLIENT.listObjects(io.minio.ListObjectsArgs.builder().bucket("invoice-documents")
                .prefix("originals/" + caseId + "/").recursive(true).build()).iterator().hasNext()).isFalse();
    }

    @Test
    void completingBeforeSubmitMakesTheStaleSubmitFail() throws Exception {
        JsonNode created = createCase("c8", "INV-8");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d8", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        long staleVersion = currentCaseVersion(caseId);
        completeDocument(caseId, revision, "doc-8", staleVersion, pdf, "a.pdf", DocumentPolicy.PDF);

        MvcResult stale = submit(caseId, "s8", staleVersion);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(stale.getResponse().getContentAsByteArray()).get("code").asText())
                .isEqualTo("STALE_CASE_VERSION");
        assertThat(jdbc.queryForObject("select count(*) from evidence_bundle", Long.class)).isZero();
    }

    @Test
    void auditFailureRollsBackSubmissionSealBundleAndIdempotencyThenSameRequestSucceeds() throws Exception {
        JsonNode created = createCase("c9", "INV-9");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d9", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        jdbc.execute("alter table audit_entry add constraint test_submit_audit_failure"
                + " check (action <> 'CASE_SUBMITTED')");
        long version = currentCaseVersion(caseId);
        try {
            MvcResult failed = submit(caseId, "s9", version);
            assertThat(failed.getResponse().getStatus()).isEqualTo(409);
            assertThat(jdbc.queryForObject("select count(*) from evidence_bundle", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='invoice-case:submit'",
                    Long.class)).isZero();
            assertThat(jdbc.queryForObject("select status from draft_revision where id = ?", String.class, revision))
                    .isEqualTo("OPEN");
        } finally {
            jdbc.execute("alter table audit_entry drop constraint test_submit_audit_failure");
        }
        assertThat(submit(caseId, "s9", version).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from evidence_bundle", Long.class)).isEqualTo(1L);
    }

    @Test
    void submitReplayIsExactAndChangedPayloadConflicts() throws Exception {
        JsonNode created = createCase("c10", "INV-10");
        UUID caseId = UUID.fromString(created.get("id").asText());
        replaceDraft(caseId, "d10", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        long version = currentCaseVersion(caseId);

        MvcResult first = submit(caseId, "s10", version);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        MvcResult replay = submit(caseId, "s10", version);
        assertThat(replay.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());

        MvcResult conflict = submit(caseId, "s10", version + 1);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(conflict.getResponse().getContentAsByteArray()).get("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from evidence_bundle", Long.class)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Approval boundary
    // ------------------------------------------------------------------

    @Test
    void documentBearingReviewAndApprovalSucceeds() throws Exception {
        JsonNode created = createCase("c11", "INV-11");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d11", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        completeDocument(caseId, revision, "doc-11", currentCaseVersion(caseId), pdf, "a.pdf", DocumentPolicy.PDF);
        submit(caseId, "s11", currentCaseVersion(caseId));
        runMatch(caseId, "m11");
        JsonNode snapshot = freeze(caseId, "f11");

        MvcResult approved = approve(caseId, "a11", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText());
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from payment_request", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from receipt_allocation", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isEqualTo(1L);
    }

    @Test
    void forgedDocumentOmissionTamperAndUnknownSchemaAreRejectedWithoutSideEffects() throws Exception {
        JsonNode created = createCase("c12", "INV-12");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d12", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        completeDocument(caseId, revision, "doc-12", currentCaseVersion(caseId), pdf, "a.pdf", DocumentPolicy.PDF);
        submit(caseId, "s12", currentCaseVersion(caseId));
        runMatch(caseId, "m12");
        JsonNode real = freeze(caseId, "f12");

        // A self-consistent document-v2 bundle that omits the frozen document.
        InvoiceCase invoiceCase = invoiceCases.findById(caseId).orElseThrow();
        List<InvoiceLine> lines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(revision);
        var omission = bundleHasher.canonicalize(invoiceCase, 1, lines);
        insertForgedBundle(caseId, revision, 2, omission.json(), omission.hash());

        // A document-v2 bundle whose document metadata is tampered but whose
        // own hash is internally correct.
        var tamper = bundleHasher.canonicalize(invoiceCase, 1, lines, List.of(
                new com.invoicematch.core.document.domain.DocumentEvidence(
                        jdbc.queryForObject("select d.id from document d join document_upload u on u.id = d.id"
                                + " where u.invoice_case_id = ?", UUID.class, caseId),
                        revision,
                        "a.pdf",
                        DocumentPolicy.PDF,
                        pdf.length,
                        "0000000000000000000000000000000000000000000000000000000000000000")));
        insertForgedBundle(caseId, revision, 3, tamper.json(), tamper.hash());

        for (int version : List.of(2, 3)) {
            UUID forgedId = jdbc.queryForObject(
                    "select id from evidence_bundle where invoice_case_id = ? and version_number = ?",
                    UUID.class, caseId, version);
            UUID forgedMatch = UUID.randomUUID();
            jdbc.update(
                    "insert into match_result (id, invoice_case_id, evidence_bundle_id, result_number, result_hash,"
                            + " purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark,"
                            + " payload, created_at)"
                            + " select ?, invoice_case_id, ?, ?, 'deadbeef', purchasing_snapshot_version,"
                            + " purchasing_snapshot_hash, mapping_watermark, payload, now()"
                            + " from match_result where invoice_case_id = ? and result_number = 1",
                    forgedMatch, forgedId, version, caseId);
            UUID forgedSnapshot = UUID.randomUUID();
            jdbc.update(
                    "insert into review_snapshot (id, invoice_case_id, evidence_bundle_id, match_result_id,"
                            + " match_result_number, snapshot_number, target_case_version,"
                            + " target_evidence_bundle_version, purchasing_snapshot_version, purchasing_snapshot_hash,"
                            + " mapping_watermark, payload_hash, payload, created_at)"
                            + " select ?, invoice_case_id, ?, ?, ?, ?, target_case_version, ?,"
                            + " purchasing_snapshot_version, purchasing_snapshot_hash, mapping_watermark,"
                            + " payload_hash, payload, now()"
                            + " from review_snapshot where id = ?",
                    forgedSnapshot, forgedId, forgedMatch, version, 10 + version, version,
                    UUID.fromString(real.get("id").asText()));

            MvcResult rejected = perform(post("/api/invoice-cases/{id}/approve", caseId)
                            .with(user("approver").roles("APPROVER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(approveBody("forged-" + version,
                                    currentCaseVersion(caseId), forgedSnapshot.toString(),
                                    real.get("payloadHash").asText()))));
            assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select count(*) from payment_request", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from receipt_allocation", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select status from invoice_case where id = ?", String.class, caseId))
                .isEqualTo("REVIEW_PENDING");
    }

    @Test
    void historicalLegacyBundleWithRegisteredDocumentsStillApprovesWithoutRetroactiveInclusion()
            throws Exception {
        JsonNode created = createCase("c13", "INV-13");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d13", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        completeDocument(caseId, revision, "doc-13", currentCaseVersion(caseId), pdf, "a.pdf", DocumentPolicy.PDF);

        // Simulate a P2-01 sealed submission: a legacy bundle with no documents
        // even though a document was registered in the revision.
        InvoiceCase invoiceCase = invoiceCases.findById(caseId).orElseThrow();
        List<InvoiceLine> lines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(revision);
        var legacy = bundleHasher.canonicalize(invoiceCase, 1, lines);
        jdbc.execute("alter table evidence_bundle disable trigger trg_evidence_bundle_payload_schema");
        try {
            jdbc.update("update draft_revision set status = 'SEALED', sealed_at = now() where id = ?", revision);
            jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                    + " payload_schema, payload_hash, payload, submitted_at)"
                    + " values (?, ?, ?, 1, 'legacy-v1', ?, cast(? as jsonb), now())",
                    UUID.randomUUID(), caseId, revision, legacy.hash(), legacy.json());
        } finally {
            jdbc.execute("alter table evidence_bundle enable trigger trg_evidence_bundle_payload_schema");
        }
        jdbc.update("update invoice_case set status = 'REVIEW_PENDING', current_draft_revision_id = null,"
                + " version = version + 1, submitted_at = now(), updated_at = now() where id = ?", caseId);

        runMatch(caseId, "m13");
        JsonNode snapshot = freeze(caseId, "f13");
        MvcResult approved = approve(caseId, "a13", currentCaseVersion(caseId),
                snapshot.get("id").asText(), snapshot.get("payloadHash").asText());

        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from payment_request", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select payload ::text like '%\"documents\"%' from evidence_bundle"
                + " where invoice_case_id = ?", Boolean.class, caseId)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from draft_revision_document where draft_revision_id = ?",
                Long.class, revision)).isEqualTo(1L);
    }

    @Test
    void supplementOfAHistoricalLegacyBundleInheritsNoBackfilledDocuments() throws Exception {
        JsonNode created = createCase("c14", "INV-14");
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d14", created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        completeDocument(caseId, revision, "doc-14", currentCaseVersion(caseId), pdf, "a.pdf", DocumentPolicy.PDF);

        // Historical P2-01-style state: the sealed revision holds a document
        // reference, but the frozen submission is legacy and document-less.
        InvoiceCase invoiceCase = invoiceCases.findById(caseId).orElseThrow();
        List<InvoiceLine> lines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(revision);
        var legacy = bundleHasher.canonicalize(invoiceCase, 1, lines);
        jdbc.execute("alter table evidence_bundle disable trigger trg_evidence_bundle_payload_schema");
        try {
            jdbc.update("update draft_revision set status = 'SEALED', sealed_at = now() where id = ?", revision);
            jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                            + " payload_schema, payload_hash, payload, submitted_at)"
                            + " values (?, ?, ?, 1, 'legacy-v1', ?, cast(? as jsonb), now())",
                    UUID.randomUUID(), caseId, revision, legacy.hash(), legacy.json());
        } finally {
            jdbc.execute("alter table evidence_bundle enable trigger trg_evidence_bundle_payload_schema");
        }
        jdbc.update("update invoice_case set status = 'REVIEW_PENDING', current_draft_revision_id = null,"
                + " version = version + 1, submitted_at = now(), updated_at = now() where id = ?", caseId);

        runMatch(caseId, "m14");
        JsonNode snapshot = freeze(caseId, "f14");
        requestSupplement(caseId, "sup14", snapshot.get("id").asText(), snapshot.get("payloadHash").asText());
        JsonNode revised = openRevision(caseId, "r14");
        UUID revision2 = UUID.fromString(revised.get("currentRevision").get("id").asText());

        assertThat(jdbc.queryForObject("select count(*) from draft_revision_document where draft_revision_id = ?",
                Long.class, revision2)).isZero();
        // The historical sealed reference itself is preserved.
        assertThat(jdbc.queryForObject("select count(*) from draft_revision_document where draft_revision_id = ?",
                Long.class, revision)).isEqualTo(1L);
    }

    @Test
    void supplementRejectsForgedV2SchemaAndLegacyDisguiseWithoutEffects() throws Exception {
        // Every fixture is a fully valid manifest built from the real authoritative
        // document metadata, with exactly one defect injected, so the assertion
        // proves that defect is the rejection cause.
        UUID badSchema = forgedSupplementCase("bad1", "document-v2",
                manifest -> manifest(manifest, "\"schemaVersion\":999", documents(manifest)));
        assertSupplementRejected(badSchema, "bad1");

        UUID missingDocuments = forgedSupplementCase("bad2", "document-v2",
                manifest -> "{" + header(manifest) + ",\"schemaVersion\":2,\"lines\":[]}");
        assertSupplementRejected(missingDocuments, "bad2");

        UUID duplicated = forgedSupplementCase("bad3", "document-v2",
                manifest -> manifest(manifest, "\"schemaVersion\":2",
                        "[" + documentEntry(manifest) + "," + documentEntry(manifest) + "]"));
        assertSupplementRejected(duplicated, "bad3");

        UUID legacyDisguise = forgedSupplementCase("bad4", "legacy-v1",
                manifest -> manifest(manifest, "\"schemaVersion\":2", documents(manifest)));
        assertSupplementRejected(legacyDisguise, "bad4");

        UUID legacyDocumentsField = forgedSupplementCase("bad5", "legacy-v1",
                manifest -> manifest(manifest, null, "[]"));
        assertSupplementRejected(legacyDocumentsField, "bad5");
    }

    private record ForgedManifest(UUID caseId, UUID documentId, UUID revisionId) {
    }

    private UUID forgedSupplementCase(
            String suffix,
            String payloadSchema,
            java.util.function.Function<ForgedManifest, String> payloadBuilder)
            throws Exception {
        JsonNode created = createCase("c-" + suffix, "INV-" + suffix);
        UUID caseId = UUID.fromString(created.get("id").asText());
        UUID revision = UUID.fromString(created.get("currentRevision").get("id").asText());
        replaceDraft(caseId, "d-" + suffix, created.get("version").asLong(), 1, 60, 2500, ITEM_A);
        JsonNode document = completeDocument(caseId, revision, "doc-" + suffix, currentCaseVersion(caseId), pdf,
                "a.pdf", DocumentPolicy.PDF);
        String payload = payloadBuilder.apply(
                new ForgedManifest(caseId, UUID.fromString(document.get("documentId").asText()), revision));

        boolean legacy = "legacy-v1".equals(payloadSchema);
        if (legacy) {
            jdbc.execute("alter table evidence_bundle disable trigger trg_evidence_bundle_payload_schema");
        }
        try {
            jdbc.update("update draft_revision set status = 'SEALED', sealed_at = now() where id = ?", revision);
            jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                            + " payload_schema, payload_hash, payload, submitted_at)"
                            + " values (?, ?, ?, 1, ?, 'forged-hash', cast(? as jsonb), now())",
                    UUID.randomUUID(), caseId, revision, payloadSchema, payload);
        } finally {
            if (legacy) {
                jdbc.execute("alter table evidence_bundle enable trigger trg_evidence_bundle_payload_schema");
            }
        }
        jdbc.update("update invoice_case set status = 'SUPPLEMENT_REQUIRED', current_draft_revision_id = null,"
                + " version = version + 1, submitted_at = now(), updated_at = now() where id = ?", caseId);
        return caseId;
    }

    private void assertSupplementRejected(UUID caseId, String suffix) throws Exception {
        long versionBefore = currentCaseVersion(caseId);
        MvcResult result = openRevisionRaw(caseId, "r-" + suffix, versionBefore);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(409);
        assertThat(currentCaseVersion(caseId)).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("select count(*) from draft_revision where invoice_case_id = ?",
                Long.class, caseId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from draft_revision_document where invoice_case_id = ?",
                Long.class, caseId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from invoice_case where id = ?", String.class, caseId))
                .isEqualTo("SUPPLEMENT_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from audit_entry"
                + " where invoice_case_id = ? and action = 'SUPPLEMENT_REVISION_OPENED'", Long.class, caseId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record"
                + " where scope = 'invoice-case:revision:open' and resource_key = ?", Long.class, caseId.toString()))
                .isZero();
    }

    private static String header(ForgedManifest manifest) {
        return "\"caseId\":\"" + manifest.caseId() + "\",\"supplierId\":\"SUP-1\","
                + "\"purchaseOrderId\":\"PO-1001\",\"invoiceNumber\":\"INV-x\",\"revisionNumber\":1";
    }

    private String manifest(ForgedManifest manifest, String schemaVersionFragment, String documentsJson) {
        return "{" + header(manifest) + ",\"lines\":[]"
                + (schemaVersionFragment == null ? "" : "," + schemaVersionFragment)
                + ",\"documents\":" + documentsJson + "}";
    }

    private String documents(ForgedManifest manifest) {
        return "[" + documentEntry(manifest) + "]";
    }

    private String documentEntry(ForgedManifest manifest) {
        return "{\"documentId\":\"" + manifest.documentId() + "\","
                + "\"sourceDraftRevisionId\":\"" + manifest.revisionId() + "\","
                + "\"fileName\":\"a.pdf\",\"mediaType\":\"application/pdf\","
                + "\"sizeBytes\":" + pdf.length + ",\"checksum\":\"" + DocumentPolicy.hash(pdf) + "\"}";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private JsonNode createCase(String requestId, String invoiceNumber) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        body.put("supplierId", SUPPLIER);
        body.put("purchaseOrderId", PO_ID);
        body.put("invoiceNumber", invoiceNumber);
        MvcResult result = perform(post("/api/invoice-cases")
                .with(user("submitter").roles("SUBMITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    private void replaceDraft(UUID caseId, String requestId, long version, int lineNumber, int quantity, long unitPrice,
            String confirmedItemId) throws Exception {
        ObjectNode line = json.createObjectNode();
        line.put("lineNumber", lineNumber);
        line.put("rawItemName", "Premium Copy Paper A4");
        line.put("quantity", quantity);
        line.put("unitPrice", unitPrice);
        line.put("confirmedItemId", confirmedItemId);
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", version);
        body.putArray("lines").add(line);
        MvcResult result = perform(put("/api/invoice-cases/{id}/draft", caseId)
                .with(user("submitter").roles("SUBMITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult submit(UUID caseId, String requestId, long version) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", version);
        return perform(post("/api/invoice-cases/{id}/submit", caseId)
                .with(user("submitter").roles("SUBMITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private Map<String, Object> reserveBody(UUID caseId, UUID revision, String requestId, long version, String name,
            String mediaType) {
        String checksum = "0".repeat(64);
        return Map.of("requestId", requestId, "expectedCaseVersion", version, "draftRevisionId", revision,
                "fileName", name, "mediaType", mediaType, "sizeBytes", 4L, "checksum", checksum);
    }

    private JsonNode reserveDocument(UUID caseId, UUID revision, String requestId, long version, String name,
            String mediaType) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", version);
        body.put("draftRevisionId", revision);
        body.put("fileName", name);
        body.put("mediaType", mediaType);
        body.put("sizeBytes", (long) pdf.length);
        body.put("checksum", DocumentPolicy.hash(pdf));
        MvcResult result = perform(post("/api/invoice-cases/{id}/documents/presign", caseId)
                .with(user("submitter").roles("SUBMITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    private JsonNode completeDocument(UUID caseId, UUID revision, String requestId, long version, byte[] bytes,
            String name, String mediaType) throws Exception {
        JsonNode upload = reserveFor(caseId, revision, requestId, version, bytes, name, mediaType);
        putBytes(upload, bytes, mediaType);
        MvcResult result = complete(caseId, upload, "complete-" + requestId, version, bytes);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    private JsonNode reserveFor(UUID caseId, UUID revision, String requestId, long version, byte[] bytes, String name,
            String mediaType) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "reserve-" + requestId);
        body.put("expectedCaseVersion", version);
        body.put("draftRevisionId", revision);
        body.put("fileName", name);
        body.put("mediaType", mediaType);
        body.put("sizeBytes", (long) bytes.length);
        body.put("checksum", DocumentPolicy.hash(bytes));
        MvcResult result = perform(post("/api/invoice-cases/{id}/documents/presign", caseId)
                .with(user("submitter").roles("SUBMITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        return body(result);
    }

    private void putBytes(JsonNode upload, byte[] bytes, String mediaType) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(upload.get("uploadUrl").asText())).timeout(Duration.ofSeconds(10))
                .header("Content-Type", mediaType).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private MvcResult complete(UUID caseId, JsonNode upload, String requestId, long version, byte[] bytes)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", version);
        body.put("draftRevisionId", upload.get("draftRevisionId").asText());
        body.put("documentId", upload.get("documentId").asText());
        body.put("checksum", DocumentPolicy.hash(bytes));
        return perform(post("/api/invoice-cases/{id}/documents/complete", caseId)
                .with(user("submitter").roles("SUBMITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private void runMatch(UUID caseId, String requestId) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        MvcResult result = perform(post("/api/invoice-cases/{id}/match", caseId)
                .with(user("operator").roles("OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private JsonNode freeze(UUID caseId, String requestId) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", currentCaseVersion(caseId));
        MvcResult result = perform(post("/api/invoice-cases/{id}/review-snapshots", caseId)
                .with(user("approver").roles("APPROVER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    private void requestSupplement(UUID caseId, String requestId, String snapshotId, String payloadHash)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", currentCaseVersion(caseId));
        body.put("reviewSnapshotId", snapshotId);
        body.put("reviewPayloadHash", payloadHash);
        body.put("reason", "need the corrected document");
        MvcResult result = perform(post("/api/invoice-cases/{id}/supplement-requests", caseId)
                .with(user("approver").roles("APPROVER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    private JsonNode openRevision(UUID caseId, String requestId) throws Exception {
        MvcResult result = openRevisionRaw(caseId, requestId, currentCaseVersion(caseId));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    private MvcResult openRevisionRaw(UUID caseId, String requestId, long version) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", version);
        return perform(post("/api/invoice-cases/{id}/revisions", caseId)
                .with(user("submitter").roles("SUBMITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private MvcResult approve(UUID caseId, String requestId, long version, String snapshotId, String payloadHash)
            throws Exception {
        return perform(post("/api/invoice-cases/{id}/approve", caseId)
                .with(user("approver").roles("APPROVER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(approveBody(requestId, version, snapshotId, payloadHash))))
                ;
    }

    private ObjectNode approveBody(String requestId, long version, String snapshotId, String payloadHash) {
        ObjectNode body = json.createObjectNode();
        body.put("requestId", requestId);
        body.put("expectedCaseVersion", version);
        body.put("reviewSnapshotId", snapshotId);
        body.put("reviewPayloadHash", payloadHash);
        return body;
    }

    private long currentCaseVersion(UUID caseId) throws Exception {
        MvcResult result = perform(get("/api/invoice-cases/{id}", caseId)
                .with(user("submitter").roles("SUBMITTER")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result).get("version").asLong();
    }

    private byte[] originalBytes(UUID documentId) throws Exception {
        String key = jdbc.queryForObject("select object_key from document where id = ?", String.class, documentId);
        try (var object = CLIENT.getObject(io.minio.GetObjectArgs.builder().bucket("invoice-documents")
                .object(key).build())) {
            return object.readAllBytes();
        }
    }

    private void insertForgedBundle(UUID caseId, UUID revision, int version, String payload, String hash) {
        jdbc.update("insert into evidence_bundle (id, invoice_case_id, draft_revision_id, version_number,"
                + " payload_schema, payload_hash, payload, submitted_at)"
                + " values (?, ?, ?, ?, 'document-v2', ?, cast(? as jsonb), now())",
                UUID.randomUUID(), caseId, revision, version, hash, payload);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mvc.perform(builder).andReturn();
    }
}
