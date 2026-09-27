package com.invoicematch.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceCaseDetail;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.domain.StaleCaseVersionException;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.application.RecordMappingDecisionCommand;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Direct service calls (no HTTP filter chain) must still be authorized at the
 * transaction boundary: wrong role or non-owner is rejected after the case lock
 * and leaves no business, audit or idempotency side effect.
 */
class WriteServiceAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUPPLIER = "SUP-1";
    private static final String PO_ID = "PO-1001";
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
    private MatchingService matching;

    @Autowired
    private ReviewService review;

    @Autowired
    private InvoiceCaseRepository invoiceCases;

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

    @AfterEach
    void clearActor() {
        TestActors.clear();
    }

    @Test
    void createRequiresSubmitterInsideTheServiceBoundary() {
        TestActors.as("operator", "OPERATOR");

        assertThatThrownBy(() -> commands.create(
                        new CreateInvoiceCaseCommand("req-1", SUPPLIER, PO_ID, "INV-1")))
                .isInstanceOf(ForbiddenActionException.class);

        assertThat(count("invoice_case")).isZero();
        assertThat(count("idempotency_record")).isZero();
        assertThat(count("audit_entry")).isZero();
    }

    @Test
    void draftWriteRequiresOwnershipAfterTheCaseLock() {
        UUID caseId = draftCase();
        long version = version(caseId);
        int linesBefore = count("invoice_line");
        int auditsBefore = count("audit_entry");
        int idempotencyBefore = count("idempotency_record");

        TestActors.as("submitter2", "SUBMITTER");
        assertThatThrownBy(() -> commands.replaceDraft(new ReplaceDraftLinesCommand(
                        caseId, "req-owner", version, List.of(new InvoiceLineInput(1, "A4", 1, 100, null)))))
                .isInstanceOf(ForbiddenActionException.class);

        assertThat(count("invoice_line")).isEqualTo(linesBefore);
        assertThat(count("audit_entry")).isEqualTo(auditsBefore);
        assertThat(count("idempotency_record")).isEqualTo(idempotencyBefore);
    }

    @Test
    void draftWriteRejectsTheWrongRoleEvenForTheOwner() {
        UUID caseId = draftCase();
        long version = version(caseId);
        int linesBefore = count("invoice_line");
        int idempotencyBefore = count("idempotency_record");

        TestActors.as("submitter", "OPERATOR");
        assertThatThrownBy(() -> commands.replaceDraft(new ReplaceDraftLinesCommand(
                        caseId, "req-role", version, List.of(new InvoiceLineInput(1, "A4", 1, 100, null)))))
                .isInstanceOf(ForbiddenActionException.class);

        assertThat(count("invoice_line")).isEqualTo(linesBefore);
        assertThat(count("idempotency_record")).isEqualTo(idempotencyBefore);
    }

    @Test
    void matchRequiresOperator() {
        UUID caseId = submittedCase();
        int matchesBefore = count("match_result");
        int idempotencyBefore = count("idempotency_record");

        TestActors.as("approver", "APPROVER");
        assertThatThrownBy(() -> matching.run(new RunMatchCommand(caseId, "m-1")))
                .isInstanceOf(ForbiddenActionException.class);

        assertThat(count("match_result")).isEqualTo(matchesBefore);
        assertThat(count("idempotency_record")).isEqualTo(idempotencyBefore);
    }

    @Test
    void freezeSnapshotRequiresApprover() {
        UUID caseId = submittedCase();
        TestActors.run("operator", "OPERATOR", () -> matching.run(new RunMatchCommand(caseId, "m-1")));
        int snapshotsBefore = count("review_snapshot");

        TestActors.as("operator", "OPERATOR");
        assertThatThrownBy(() -> review.freezeSnapshot(
                        new FreezeReviewSnapshotCommand(caseId, "s-1", version(caseId))))
                .isInstanceOf(ForbiddenActionException.class);

        assertThat(count("review_snapshot")).isEqualTo(snapshotsBefore);
    }

    @Test
    void explicitOperatorRunStillAppendsAnAuditedMatch() {
        UUID caseId = submittedCase();
        int matchesBefore = count("match_result");
        int matchRunBefore = auditCount("MATCH_RUN");

        TestActors.as("operator", "OPERATOR");
        matching.run(new RunMatchCommand(caseId, "m-op"));

        assertThat(count("match_result")).isEqualTo(matchesBefore + 1);
        assertThat(auditCount("MATCH_RUN")).isEqualTo(matchRunBefore + 1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_entry where action = 'MATCH_RUN' and request_id = 'm-op'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void mappingCreatesExactlyOneMatchAndSnapshotWithAtomicItemMappedAudit() {
        UUID caseId = frozenCase();
        ReviewSnapshot snapshot = latestSnapshot(caseId);
        int matchesBefore = count("match_result");
        int snapshotsBefore = count("review_snapshot");
        int itemMappedBefore = auditCount("ITEM_MAPPED");
        int matchRunBefore = auditCount("MATCH_RUN");

        TestActors.as("approver", "APPROVER");
        review.recordMapping(new RecordMappingDecisionCommand(
                caseId, "map-1", version(caseId), snapshot.id(), snapshot.payloadHash(), 1, "ITEM-A4-80"));

        assertThat(count("match_result")).isEqualTo(matchesBefore + 1);
        assertThat(count("review_snapshot")).isEqualTo(snapshotsBefore + 1);
        assertThat(auditCount("ITEM_MAPPED")).isEqualTo(itemMappedBefore + 1);
        // The internal re-match is covered by the atomic ITEM_MAPPED event, not a
        // standalone operator-style MATCH_RUN.
        assertThat(auditCount("MATCH_RUN")).isEqualTo(matchRunBefore);
    }

    @Test
    void mappingReplayAddsNoMatchSnapshotOrAudit() {
        UUID caseId = frozenCase();
        ReviewSnapshot snapshot = latestSnapshot(caseId);
        RecordMappingDecisionCommand command = new RecordMappingDecisionCommand(
                caseId, "map-replay", version(caseId), snapshot.id(), snapshot.payloadHash(), 1, "ITEM-A4-80");

        TestActors.as("approver", "APPROVER");
        review.recordMapping(command);
        int matches = count("match_result");
        int snapshots = count("review_snapshot");
        int audits = count("audit_entry");

        review.recordMapping(command);

        assertThat(count("match_result")).isEqualTo(matches);
        assertThat(count("review_snapshot")).isEqualTo(snapshots);
        assertThat(count("audit_entry")).isEqualTo(audits);
    }

    @Test
    void mappingFailureRollsBackMatchSnapshotAndAudit() {
        UUID caseId = frozenCase();
        ReviewSnapshot snapshot = latestSnapshot(caseId);
        int matches = count("match_result");
        int snapshots = count("review_snapshot");
        int audits = count("audit_entry");

        TestActors.as("approver", "APPROVER");
        assertThatThrownBy(() -> review.recordMapping(new RecordMappingDecisionCommand(
                        caseId, "map-stale", version(caseId) + 100, snapshot.id(), snapshot.payloadHash(),
                        1, "ITEM-A4-80")))
                .isInstanceOf(StaleCaseVersionException.class);

        assertThat(count("match_result")).isEqualTo(matches);
        assertThat(count("review_snapshot")).isEqualTo(snapshots);
        assertThat(count("audit_entry")).isEqualTo(audits);
    }

    private UUID frozenCase() {
        UUID caseId = submittedCase();
        TestActors.run("operator", "OPERATOR", () -> matching.run(new RunMatchCommand(caseId, "m-1")));
        TestActors.run("approver", "APPROVER", () -> review.freezeSnapshot(
                new FreezeReviewSnapshotCommand(caseId, "s-1", version(caseId))));
        return caseId;
    }

    private ReviewSnapshot latestSnapshot(UUID caseId) {
        return reviewSnapshots.findFirstByInvoiceCaseIdOrderBySnapshotNumberDesc(caseId).orElseThrow();
    }

    private int auditCount(String action) {
        return jdbc.queryForObject(
                "select count(*) from audit_entry where action = ?", Integer.class, action);
    }

    private UUID draftCase() {
        return TestActors.call("submitter", "SUBMITTER", () -> {
            InvoiceCaseDetail created = commands
                    .create(new CreateInvoiceCaseCommand("c-1", SUPPLIER, PO_ID, "INV-1"))
                    .body();
            UUID caseId = created.id();
            commands.replaceDraft(new ReplaceDraftLinesCommand(
                    caseId,
                    "c-2",
                    created.version(),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", 10, 2500, null))));
            return caseId;
        });
    }

    private UUID submittedCase() {
        return TestActors.call("submitter", "SUBMITTER", () -> {
            InvoiceCaseDetail created = commands
                    .create(new CreateInvoiceCaseCommand("c-1", SUPPLIER, PO_ID, "INV-1"))
                    .body();
            UUID caseId = created.id();
            commands.replaceDraft(new ReplaceDraftLinesCommand(
                    caseId,
                    "c-2",
                    created.version(),
                    List.of(new InvoiceLineInput(1, "Premium Copy Paper A4", 10, 2500, null))));
            commands.submit(new SubmitInvoiceCaseCommand(caseId, "c-3", version(caseId)));
            return caseId;
        });
    }

    private long version(UUID caseId) {
        return invoiceCases.findById(caseId).orElseThrow().version();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
