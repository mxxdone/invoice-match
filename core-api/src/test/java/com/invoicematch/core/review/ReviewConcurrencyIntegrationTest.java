package com.invoicematch.core.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.application.MappingDecisionResult;
import com.invoicematch.core.review.application.RecordMappingDecisionCommand;
import com.invoicematch.core.review.application.RequestSupplementCommand;
import com.invoicematch.core.review.application.ReviewService;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import com.invoicematch.core.support.TestActors;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real PostgreSQL concurrency tests for P1-05: the invoice case row lock and
 * request-id reservation must keep mapping, supplement and snapshot freezing
 * consistent under concurrent identical requests and competing human actions.
 */
class ReviewConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
    private static final String ITEM_A = "ITEM-A4-80";
    private static final StubPurchasingServer STUB;

    static {
        try {
            STUB = new StubPurchasingServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void purchasingProperties(DynamicPropertyRegistry registry) {
        registry.add("purchasing-system.base-url", STUB::baseUrl);
        registry.add("purchasing-system.connect-timeout", () -> "1s");
        registry.add("purchasing-system.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private InvoiceCaseApplicationService invoiceCaseCommands;

    @Autowired
    private InvoiceCaseRepository invoiceCaseRepository;

    @Autowired
    private ReviewSnapshotRepository reviewSnapshots;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        STUB.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
    }

    @Test
    void concurrentIdenticalMappingRequestCreatesOneDecisionAndSnapshot() throws Exception {
        UUID caseId = frozenCase();
        ReviewSnapshot snapshot = latestSnapshot(caseId);
        long version = caseVersion(caseId);

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MappingDecisionResult> task = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return TestActors.call("approver", "APPROVER", () -> reviewService.recordMapping(
                                new RecordMappingDecisionCommand(
                                        caseId, "map-same", version, snapshot.id(), snapshot.payloadHash(), 1, ITEM_A)))
                        .body();
            };
            Future<MappingDecisionResult> first = pool.submit(task);
            Future<MappingDecisionResult> second = pool.submit(task);
            gate.countDown();

            MappingDecisionResult a = first.get(30, TimeUnit.SECONDS);
            MappingDecisionResult b = second.get(30, TimeUnit.SECONDS);

            assertThat(a.decision().id()).isEqualTo(b.decision().id());
            assertThat(count("review_decision")).isEqualTo(1);
            assertThat(count("review_snapshot")).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentMappingAndSupplementOnlyOneSucceeds() throws Exception {
        UUID caseId = frozenCase();
        ReviewSnapshot snapshot = latestSnapshot(caseId);
        long version = caseVersion(caseId);

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            Future<Void> mapping = pool.submit(() -> race(gate, successes, conflicts, () ->
                    TestActors.call("approver", "APPROVER", () -> {
                        reviewService.recordMapping(new RecordMappingDecisionCommand(
                                caseId, "map-race", version, snapshot.id(), snapshot.payloadHash(), 1, ITEM_A));
                        return null;
                    })));
            Future<Void> supplement = pool.submit(() -> race(gate, successes, conflicts, () ->
                    TestActors.call("approver", "APPROVER", () -> {
                        reviewService.requestSupplement(new RequestSupplementCommand(
                                caseId, "supp-race", version, snapshot.id(), snapshot.payloadHash(), "need correction"));
                        return null;
                    })));
            gate.countDown();
            mapping.get(30, TimeUnit.SECONDS);
            supplement.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(count("review_decision")).isEqualTo(1);

        String status = status(caseId);
        if (status.equals("SUPPLEMENT_REQUIRED")) {
            assertThat(count("review_snapshot")).isEqualTo(1);
        } else {
            assertThat(status).isEqualTo("REVIEW_PENDING");
            assertThat(count("review_snapshot")).isEqualTo(2);
        }
    }

    @Test
    void concurrentFreezesGetDistinctMonotonicSnapshotNumbers() throws Exception {
        UUID caseId = frozenCase();

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> {
                gate.await(10, TimeUnit.SECONDS);
                return TestActors.call("approver", "APPROVER", () -> reviewService.freezeSnapshot(
                                new FreezeReviewSnapshotCommand(
                                        caseId, UUID.randomUUID().toString(), caseVersion(caseId))))
                        .body()
                        .snapshotNumber();
            };
            Future<Integer> first = pool.submit(task);
            Future<Integer> second = pool.submit(task);
            gate.countDown();

            List<Integer> numbers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(numbers).containsExactlyInAnyOrder(2, 3);
            assertThat(count("review_snapshot")).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID frozenCase() {
        UUID caseId = TestActors.call("submitter", "SUBMITTER", () -> {
            CreateInvoiceCaseCommand create = new CreateInvoiceCaseCommand("c-1", SUPPLIER, PO_ID, "INV-1");
            UUID id = invoiceCaseCommands.create(create).body().id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    id,
                    "c-2",
                    caseVersion(id),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", 10, 2500, null))));
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(id, "c-3", caseVersion(id)));
            return id;
        });
        TestActors.run("operator", "OPERATOR", () -> matchingService.run(new RunMatchCommand(caseId, "m-1")));
        TestActors.run("approver", "APPROVER", () -> reviewService.freezeSnapshot(
                new FreezeReviewSnapshotCommand(caseId, "s-1", caseVersion(caseId))));
        return caseId;
    }

    private <T> T race(CountDownLatch gate, AtomicInteger successes, AtomicInteger conflicts, Callable<T> action)
            throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        try {
            T result = action.call();
            successes.incrementAndGet();
            return result;
        } catch (RuntimeException e) {
            conflicts.incrementAndGet();
            return null;
        }
    }

    private ReviewSnapshot latestSnapshot(UUID caseId) {
        return reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId).orElseThrow();
    }

    private long caseVersion(UUID caseId) {
        return invoiceCaseRepository.findById(caseId).orElseThrow().version();
    }

    private String status(UUID caseId) {
        return invoiceCaseRepository.findById(caseId).orElseThrow().status().name();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
