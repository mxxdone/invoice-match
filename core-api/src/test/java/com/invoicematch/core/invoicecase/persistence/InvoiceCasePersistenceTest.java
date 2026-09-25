package com.invoicematch.core.invoicecase.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseId;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.review.domain.ReviewDecision;
import com.invoicematch.core.review.domain.ReviewDecisionType;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewDecisionRepository;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class InvoiceCasePersistenceTest extends AbstractPostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private InvoiceCaseRepository invoiceCases;

    @Autowired
    private DraftRevisionRepository draftRevisions;

    @Autowired
    private InvoiceLineRepository invoiceLines;

    @Autowired
    private EvidenceBundleRepository evidenceBundles;

    @Autowired
    private ReviewSnapshotRepository reviewSnapshots;

    @Autowired
    private ReviewDecisionRepository reviewDecisions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsCaseAndBumpsOptimisticVersion() {
        InvoiceCase created = invoiceCases.save(newCase());
        UUID caseId = created.id().value();
        long initialVersion = created.version();

        InvoiceCase managed = invoiceCases.findById(caseId).orElseThrow();
        assertThat(managed.status()).isEqualTo(InvoiceCaseStatus.DRAFT);

        managed.transitionTo(InvoiceCaseStatus.SUBMITTED, T0.plusSeconds(60));
        invoiceCases.saveAndFlush(managed);

        InvoiceCase reloaded = invoiceCases.findById(caseId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(InvoiceCaseStatus.SUBMITTED);
        assertThat(reloaded.submittedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(reloaded.version()).isGreaterThan(initialVersion);
    }

    @Test
    void rejectsStaleConcurrentCaseUpdate() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID caseId = transaction.execute(status -> invoiceCases.save(newCase()).id().value());

        InvoiceCase first = transaction.execute(status -> invoiceCases.findById(caseId).orElseThrow());
        InvoiceCase second = transaction.execute(status -> invoiceCases.findById(caseId).orElseThrow());

        first.transitionTo(InvoiceCaseStatus.SUBMITTED, T0.plusSeconds(60));
        transaction.executeWithoutResult(status -> invoiceCases.saveAndFlush(first));

        second.transitionTo(InvoiceCaseStatus.SUBMITTED, T0.plusSeconds(61));
        assertThatThrownBy(() ->
                        transaction.executeWithoutResult(status -> invoiceCases.saveAndFlush(second)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void persistsDraftRevisionLineWithMoneyAndQuantity() {
        InvoiceCase invoiceCase = invoiceCases.save(newCase());
        UUID caseId = invoiceCase.id().value();

        DraftRevision draft = draftRevisions.save(DraftRevision.open(caseId, 1, T0));
        InvoiceLine line = invoiceLines.save(InvoiceLine.create(
                UUID.randomUUID(), caseId, draft.id(), 1, "A4 Paper", Quantity.of(3), Money.of(2500), null, T0));

        InvoiceLine reloaded = invoiceLines.findById(line.id()).orElseThrow();
        assertThat(reloaded.draftRevisionId()).isEqualTo(draft.id());
        assertThat(reloaded.quantity()).isEqualTo(Quantity.of(3));
        assertThat(reloaded.unitPrice()).isEqualTo(Money.of(2500));
        assertThat(reloaded.lineTotal()).isEqualTo(Money.of(7500));

        DraftRevision reloadedDraft = draftRevisions.findById(draft.id()).orElseThrow();
        assertThat(reloadedDraft.revisionNumber()).isEqualTo(1);
        assertThat(reloadedDraft.isEditable()).isTrue();
    }

    @Test
    void persistsImmutableEvidenceSnapshotAndDecision() {
        InvoiceCase invoiceCase = invoiceCases.save(newCase());
        UUID caseId = invoiceCase.id().value();
        DraftRevision sealedDraft = DraftRevision.open(caseId, 1, T0);
        sealedDraft.seal(T0.plusSeconds(1));
        draftRevisions.save(sealedDraft);

        EvidenceBundle bundle = evidenceBundles.save(EvidenceBundle.freeze(
                UUID.randomUUID(), caseId, sealedDraft.id(), 1, "bundle-hash", "{\"lines\":[]}",
                T0.plusSeconds(1)));
        ReviewSnapshot snapshot = reviewSnapshots.save(ReviewSnapshot.freeze(
                UUID.randomUUID(), caseId, bundle.id(), null, 0L, 1, "snapshot-hash", "{\"total\":0}",
                T0.plusSeconds(2)));
        ReviewDecision decision = reviewDecisions.save(ReviewDecision.record(
                UUID.randomUUID(), caseId, snapshot.id(), ReviewDecisionType.APPROVED, "approver-1",
                "looks correct", null, "snapshot-hash", T0.plusSeconds(3)));

        assertThat(evidenceBundles.findById(bundle.id()))
                .get()
                .extracting(EvidenceBundle::payloadHash)
                .isEqualTo("bundle-hash");
        assertThat(evidenceBundles.findById(bundle.id()))
                .get()
                .extracting(EvidenceBundle::draftRevisionId)
                .isEqualTo(sealedDraft.id());
        assertThat(reviewSnapshots.findById(snapshot.id()))
                .get()
                .extracting(ReviewSnapshot::targetEvidenceBundleVersion)
                .isEqualTo(1);
        assertThat(reviewDecisions.findById(decision.id()))
                .get()
                .extracting(ReviewDecision::decision)
                .isEqualTo(ReviewDecisionType.APPROVED);
    }

    private static InvoiceCase newCase() {
        return InvoiceCase.create(
                InvoiceCaseId.newId(), SupplierId.of("SUP-1"), PurchaseOrderId.of("PO-1"), "INV-1", "INV1", T0);
    }
}
