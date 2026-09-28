package com.invoicematch.core.payment.application;

import com.invoicematch.core.approval.application.ApprovalApplicationService;
import com.invoicematch.core.approval.application.ApproveInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.application.ReviewService;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import com.invoicematch.core.support.TestActors;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared fixture for P1-08 payment-export tests: a real PostgreSQL database, a
 * stub purchasing system, and a helper that creates and approves a case so
 * exactly one NOT_SENT PaymentRequest and one READY outbox event exist.
 */
abstract class AbstractPaymentExportIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final String SUPPLIER = "SUP-1";
    protected static final String PO_ID = "PO-1001";
    protected static final String ITEM_A = "ITEM-A4-80";
    private static final StubPurchasingServer PURCHASING;

    static {
        try {
            PURCHASING = new StubPurchasingServer();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void purchasingProperties(DynamicPropertyRegistry registry) {
        registry.add("purchasing-system.base-url", PURCHASING::baseUrl);
        registry.add("purchasing-system.connect-timeout", () -> "1s");
        registry.add("purchasing-system.read-timeout", () -> "1s");
    }

    // The stub is JVM-wide and shared by every subclass of this fixture; it is
    // released by the JVM shutdown hook that Testcontainers also relies on, so no
    // subclass can close it while another still needs it.

    @Autowired
    protected InvoiceCaseApplicationService invoiceCaseCommands;

    @Autowired
    protected MatchingService matchingService;

    @Autowired
    protected ReviewService reviewService;

    @Autowired
    protected ApprovalApplicationService approvals;

    @Autowired
    protected InvoiceCaseRepository invoiceCases;

    @Autowired
    protected ReviewSnapshotRepository reviewSnapshots;

    @Autowired
    protected PurchasingReferenceService purchasingReferenceService;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("truncate table invoice_case cascade");
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("truncate table purchase_order_snapshot cascade");
        PURCHASING.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());
    }

    /** Creates, matches, freezes and approves one case, returning the case id. */
    protected UUID approvedCase(String invoiceNumber, int quantity) {
        return approvedCase(invoiceNumber, quantity, PO_ID);
    }

    protected UUID approvedCase(String invoiceNumber, int quantity, String purchaseOrderId) {
        UUID caseId = TestActors.call("submitter", "SUBMITTER", () -> {
            UUID id = invoiceCaseCommands
                    .create(new CreateInvoiceCaseCommand(
                            "create-" + invoiceNumber, SUPPLIER, purchaseOrderId, invoiceNumber))
                    .body()
                    .id();
            invoiceCaseCommands.replaceDraft(new ReplaceDraftLinesCommand(
                    id,
                    "draft-" + invoiceNumber,
                    caseVersion(id),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", quantity, 2500, ITEM_A))));
            invoiceCaseCommands.submit(new SubmitInvoiceCaseCommand(id, "submit-" + invoiceNumber, caseVersion(id)));
            return id;
        });
        TestActors.run("operator", "OPERATOR", () ->
                matchingService.run(new RunMatchCommand(caseId, "match-" + invoiceNumber)));
        TestActors.run("approver", "APPROVER", () ->
                reviewService.freezeSnapshot(new FreezeReviewSnapshotCommand(caseId, "snap-" + invoiceNumber,
                        caseVersion(caseId))));
        ReviewSnapshot snapshot = reviewSnapshots
                .findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId)
                .orElseThrow();
        TestActors.run("approver", "APPROVER", () -> approvals.approve(new ApproveInvoiceCaseCommand(
                caseId,
                "approve-" + invoiceNumber,
                caseVersion(caseId),
                snapshot.id(),
                snapshot.payloadHash())));
        return caseId;
    }

    /** Makes the purchasing stub return a confirmed aggregate for this purchase order id. */
    protected void respondPurchasing(String purchaseOrderId) {
        PURCHASING.respond(
                200, PurchasingPayloads.confirmedPartialReceipt().purchaseOrderId(purchaseOrderId).toJson());
    }

    protected long caseVersion(UUID caseId) {
        return invoiceCases.findById(caseId).orElseThrow().version();
    }

    protected String caseStatus(UUID caseId) {
        return jdbc.queryForObject("select status from invoice_case where id = ?", String.class, caseId);
    }

    protected Map<String, Object> payment(UUID caseId) {
        return jdbc.queryForMap(
                "select id, amount, currency, status, export_version, external_request_key,"
                        + " review_snapshot_id, review_payload_hash, purchase_order_id"
                        + " from payment_request where invoice_case_id = ?",
                caseId);
    }

    protected Map<String, Object> outbox(UUID caseId) {
        return jdbc.queryForMap(
                "select o.id, o.payment_request_id, o.status, o.idempotency_key, o.payload_hash,"
                        + " o.payload::text as payload, o.attempt_count, o.next_attempt_at,"
                        + " o.worker_id, o.claim_token, o.lease_expires_at, o.delivered_at"
                        + " from outbox_event o join payment_request p on p.id = o.payment_request_id"
                        + " where p.invoice_case_id = ?",
                caseId);
    }

    protected UUID outboxId(UUID caseId) {
        return (UUID) outbox(caseId).get("id");
    }

    protected int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
