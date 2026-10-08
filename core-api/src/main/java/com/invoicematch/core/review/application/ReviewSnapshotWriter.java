package com.invoicematch.core.review.application;

import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.matching.application.AppliedMapping;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.purchasingreference.application.CurrentPurchaseOrderSnapshot;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.persistence.ReviewSnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Canonical snapshot construction and persistence within the caller's locked review transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ReviewSnapshotWriter {
    private final ReviewSnapshotRepository snapshots;
    private final ReviewSnapshotPayloadBuilder payloadBuilder;
    private final EvidenceBundlePayloadHasher bundleHasher;

    ReviewSnapshotWriter(ReviewSnapshotRepository snapshots, ReviewSnapshotPayloadBuilder payloadBuilder,
            EvidenceBundlePayloadHasher bundleHasher) {
        this.snapshots = snapshots;
        this.payloadBuilder = payloadBuilder;
        this.bundleHasher = bundleHasher;
    }

    public int nextSnapshotNumber(UUID caseId) {
        return snapshots.maxSnapshotNumber(caseId) + 1;
    }

    public ReviewSnapshot buildAndSaveSnapshot(
            InvoiceCase invoiceCase,
            EvidenceBundle bundle,
            MatchResult result,
            List<AppliedMapping> appliedMappings,
            CurrentPurchaseOrderSnapshot purchasing,
            int snapshotNumber,
            Instant now) {
        return buildAndSaveSnapshot(
                invoiceCase, bundle, result, appliedMappings, purchasing, snapshotNumber, now, null);
    }

    public ReviewSnapshot buildAndSaveSnapshot(
            InvoiceCase invoiceCase,
            EvidenceBundle bundle,
            MatchResult result,
            List<AppliedMapping> appliedMappings,
            CurrentPurchaseOrderSnapshot purchasing,
            int snapshotNumber,
            Instant now,
            ProposalEvidenceReader.Reference proof) {
        EvidenceBundlePayload bundlePayload = bundleHasher.parse(bundle.payload());
        ReviewSnapshotPayloadInput input = new ReviewSnapshotPayloadInput(
                invoiceCase.id().value(),
                invoiceCase.version(),
                bundle.id(),
                bundle.versionNumber(),
                bundle.payloadHash(),
                result.id(),
                result.resultNumber(),
                result.resultHash(),
                result.mappingWatermark(),
                result.payload(),
                appliedMappings,
                bundlePayload.lines(),
                purchasing.aggregate().snapshotVersion(),
                purchasing.aggregate().purchaseOrder().version(),
                purchasing.payloadHash()).withProposal(proof);
        ReviewSnapshotPayloadBuilder.CanonicalPayload canonical = payloadBuilder.canonicalize(input);
        ReviewSnapshot snapshot = ReviewSnapshot.freeze(
                UUID.randomUUID(),
                invoiceCase.id().value(),
                bundle.id(),
                result.id(),
                result.resultNumber(),
                snapshotNumber,
                invoiceCase.version(),
                bundle.versionNumber(),
                purchasing.aggregate().snapshotVersion(),
                purchasing.payloadHash(),
                result.mappingWatermark(),
                canonical.hash(),
                canonical.json(),
                now);
        return snapshots.saveAndFlush(snapshot);
    }

}
