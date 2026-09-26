package com.invoicematch.core.invoicecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceCaseDetail;
import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.OpenSupplementRevisionCommand;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-PostgreSQL concurrency tests for the P1-03 write paths: request id
 * idempotency, lost-update prevention and single-open-revision creation.
 */
class InvoiceCaseConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private InvoiceCaseApplicationService commands;

    @Autowired
    private InvoiceCaseQueryService queries;

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
    void concurrentCreateWithSameRequestIdAppliesOnceAndReplays() throws Exception {
        int threads = 4;
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> commands
                    .create(new CreateInvoiceCaseCommand("req-shared", "SUP-1", "PO-1001", "INV-1"))
                    .body()
                    .id());
        }

        List<UUID> ids = runConcurrently(tasks);

        assertThat(ids).hasSize(threads).containsOnly(ids.get(0));
        assertThat(count("invoice_case")).isEqualTo(1);
        assertThat(count("purchase_order_snapshot")).isEqualTo(1);
    }

    @Test
    void concurrentCreateWithSameRequestIdDifferentPurchaseOrderConflictsWithoutLoserSnapshot() throws Exception {
        STUB.respondFor("PO-1001", PurchasingPayloads.confirmedPartialReceipt().toJson());
        STUB.respondFor(
                "PO-1002",
                PurchasingPayloads.confirmedPartialReceipt().purchaseOrderId("PO-1002").toJson());

        List<Callable<String>> tasks = List.of(
                createTask("req-race", "PO-1001"), createTask("req-race", "PO-1002"));

        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("OK", "IdempotencyConflictException");
        assertThat(count("invoice_case")).isEqualTo(1);
        assertThat(count("purchase_order_snapshot")).isEqualTo(1);

        String winnerPurchaseOrder =
                jdbc.queryForObject("select purchase_order_id from invoice_case", String.class);
        String loserPurchaseOrder = winnerPurchaseOrder.equals("PO-1001") ? "PO-1002" : "PO-1001";
        assertThat(jdbc.queryForObject(
                        "select count(*) from purchase_order_snapshot where purchase_order_id = ?",
                        Integer.class,
                        loserPurchaseOrder))
                .isZero();
    }

    private Callable<String> createTask(String requestId, String purchaseOrderId) {
        return () -> {
            try {
                commands.create(new CreateInvoiceCaseCommand(requestId, "SUP-1", purchaseOrderId, "INV-1"));
                return "OK";
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        };
    }

    @Test
    void concurrentDraftEditWithSameExpectedVersionKeepsOnlyOne() throws Exception {
        String caseId = newDraft("INV-1", List.of(new InvoiceLineInput(1, "A4", 1, 100, null)));
        long expectedVersion = queries.get(UUID.fromString(caseId)).version();

        List<Callable<String>> tasks = List.of(
                editTask(caseId, "req-edit-a", expectedVersion, 2),
                editTask(caseId, "req-edit-b", expectedVersion, 3));

        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("OK", "StaleCaseVersionException");
        assertThat(queries.get(UUID.fromString(caseId)).lines()).hasSize(1);
    }

    @Test
    void concurrentOpenRevisionKeepsOnlyOneOpenDraft() throws Exception {
        String caseId = newDraft("INV-1", List.of(new InvoiceLineInput(1, "A4", 1, 100, null)));
        long draftVersion = queries.get(UUID.fromString(caseId)).version();
        commands.submit(new SubmitInvoiceCaseCommand(UUID.fromString(caseId), "req-submit", draftVersion));
        forceSupplementRequired(caseId);
        long expectedVersion = queries.get(UUID.fromString(caseId)).version();

        List<Callable<String>> tasks = List.of(
                openTask(caseId, "req-open-a", expectedVersion),
                openTask(caseId, "req-open-b", expectedVersion));

        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("OK", "StaleCaseVersionException");
        assertThat(count("draft_revision where status = 'OPEN'")).isEqualTo(1);
    }

    private Callable<String> editTask(String caseId, String requestId, long expectedVersion, int quantity) {
        return () -> {
            try {
                commands.replaceDraft(new ReplaceDraftLinesCommand(
                        UUID.fromString(caseId), requestId, expectedVersion, List.of(new InvoiceLineInput(1, "A4", quantity, 100, null))));
                return "OK";
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        };
    }

    private Callable<String> openTask(String caseId, String requestId, long expectedVersion) {
        return () -> {
            try {
                commands.openSupplementRevision(
                        new OpenSupplementRevisionCommand(UUID.fromString(caseId), requestId, expectedVersion));
                return "OK";
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        };
    }

    private String newDraft(String invoiceNumber, List<InvoiceLineInput> lines) {
        CommandResult<InvoiceCaseDetail> created =
                commands.create(new CreateInvoiceCaseCommand("req-create-" + UUID.randomUUID(), "SUP-1", "PO-1001", invoiceNumber));
        String caseId = created.body().id().toString();
        commands.replaceDraft(new ReplaceDraftLinesCommand(
                UUID.fromString(caseId), "req-edit-" + UUID.randomUUID(), created.body().version(), lines));
        return caseId;
    }

    private void forceSupplementRequired(String caseId) {
        jdbc.update(
                "update invoice_case set status = 'SUPPLEMENT_REQUIRED', version = version + 1,"
                        + " updated_at = updated_at + interval '1 second' where id = ?",
                UUID.fromString(caseId));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int count(String fromClause) {
        return jdbc.queryForObject("select count(*) from " + fromClause, Integer.class);
    }
}
