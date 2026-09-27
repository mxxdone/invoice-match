package com.invoicematch.core.approval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;
import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.DraftRevisionStatus;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.invoicecase.persistence.DraftRevisionRepository;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceLineRepository;
import com.invoicematch.core.matching.application.EffectiveMappingResolver;
import com.invoicematch.core.matching.application.MatchComputation;
import com.invoicematch.core.matching.application.MatchEngine;
import com.invoicematch.core.matching.application.MatchInput;
import com.invoicematch.core.matching.domain.MatchResult;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.review.application.ReviewSnapshotPayloadBuilder;
import com.invoicematch.core.review.application.ReviewSnapshotPayloadInput;
import com.invoicematch.core.review.domain.ReviewSnapshot;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Independently reconstructs and verifies the full approval subject inside the
 * locked approval transaction, so stored snapshot/match/bundle JSON and hashes
 * are never trusted as authority:
 *
 * <ol>
 *   <li>the canonical evidence payload is rebuilt from the authoritative case
 *       header and the sealed draft revision's invoice lines and compared to the
 *       stored bundle hash/payload;</li>
 *   <li>the deterministic match is rerun with the pure {@link MatchEngine} over
 *       that authoritative evidence, the current purchasing aggregate and the
 *       effective mappings, and the complete canonical payload/hash plus source
 *       columns are compared to the stored latest match result;</li>
 *   <li>the complete canonical review snapshot is rebuilt with the existing
 *       {@link ReviewSnapshotPayloadBuilder} from those verified sources and
 *       compared (hash, canonical payload and relational source fields) to the
 *       stored target;</li>
 *   <li>the typed allocation plan and amount are derived from the recomputed
 *       typed match result, never from stored JSON.</li>
 * </ol>
 *
 * Any mismatch throws {@link ReviewStateConflictException} with no side effect.
 */
@Component
public class ApprovalSubjectVerifier {

    private final EvidenceBundleRepository evidenceBundles;
    private final DraftRevisionRepository draftRevisions;
    private final InvoiceLineRepository invoiceLines;
    private final EvidenceBundlePayloadHasher bundleHasher;
    private final MatchEngine matchEngine;
    private final EffectiveMappingResolver mappingResolver;
    private final InvoiceCaseQueryService invoiceCaseQueries;
    private final ReviewSnapshotPayloadBuilder snapshotPayloadBuilder;
    private final ApprovedAllocationPlanFactory planFactory;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalSubjectVerifier(
            EvidenceBundleRepository evidenceBundles,
            DraftRevisionRepository draftRevisions,
            InvoiceLineRepository invoiceLines,
            EvidenceBundlePayloadHasher bundleHasher,
            MatchEngine matchEngine,
            ObjectProvider<EffectiveMappingResolver> mappingResolvers,
            InvoiceCaseQueryService invoiceCaseQueries,
            ReviewSnapshotPayloadBuilder snapshotPayloadBuilder,
            ApprovedAllocationPlanFactory planFactory) {
        this.evidenceBundles = evidenceBundles;
        this.draftRevisions = draftRevisions;
        this.invoiceLines = invoiceLines;
        this.bundleHasher = bundleHasher;
        this.matchEngine = matchEngine;
        this.mappingResolver = mappingResolvers.getIfAvailable(() -> EffectiveMappingResolver.EMPTY);
        this.invoiceCaseQueries = invoiceCaseQueries;
        this.snapshotPayloadBuilder = snapshotPayloadBuilder;
        this.planFactory = planFactory;
    }

    public VerifiedApprovalSubject verify(
            InvoiceCase invoiceCase,
            ReviewSnapshot snapshot,
            MatchResult storedResult,
            PurchaseOrderAggregate purchasing,
            long purchasingSnapshotVersion,
            String purchasingSnapshotHash) {
        UUID caseId = invoiceCase.id().value();

        EvidenceBundle bundle = reconstructEvidenceBundle(invoiceCase, snapshot);
        List<InvoiceLine> sealedLines =
                invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(bundle.draftRevisionId());
        List<EvidenceBundlePayload.EvidenceLine> evidenceLines = sealedLines.stream()
                .map(ApprovalSubjectVerifier::toEvidenceLine)
                .toList();

        EffectiveMappingResolver.EffectiveMappings effective =
                mappingResolver.resolve(caseId, bundle.id());
        List<UUID> duplicateCaseIds = invoiceCaseQueries.findOtherCaseIdsWithBusinessInvoice(
                invoiceCase.supplier().value(), invoiceCase.normalizedInvoiceNumber(), caseId);

        MatchComputation computation = matchEngine.compute(new MatchInput(
                caseId,
                invoiceCase.supplier().value(),
                invoiceCase.purchaseOrder().value(),
                invoiceCase.invoiceNumber(),
                invoiceCase.normalizedInvoiceNumber(),
                invoiceCase.version(),
                bundle.id(),
                bundle.versionNumber(),
                bundle.payloadHash(),
                evidenceLines,
                purchasing,
                purchasingSnapshotHash,
                duplicateCaseIds,
                effective.mappings()));

        verifyMatchResult(caseId, snapshot, storedResult, computation, effective.watermark(),
                purchasingSnapshotVersion, purchasingSnapshotHash);

        verifyReviewSnapshot(
                invoiceCase,
                snapshot,
                bundle,
                storedResult,
                effective.mappings(),
                evidenceLines,
                purchasing,
                purchasingSnapshotVersion,
                purchasingSnapshotHash);

        ApprovedAllocationPlan plan =
                planFactory.from(caseId, snapshot.id(), computation, evidenceLines);
        return new VerifiedApprovalSubject(
                bundle, storedResult, snapshot, purchasingSnapshotVersion, purchasingSnapshotHash, plan);
    }

    private EvidenceBundle reconstructEvidenceBundle(InvoiceCase invoiceCase, ReviewSnapshot snapshot) {
        UUID caseId = invoiceCase.id().value();
        EvidenceBundle bundle = evidenceBundles
                .findById(snapshot.evidenceBundleId())
                .orElseThrow(() -> conflict(caseId, "the review snapshot evidence bundle no longer exists"));
        if (!bundle.invoiceCaseId().equals(caseId)
                || bundle.versionNumber() != snapshot.targetEvidenceBundleVersion()) {
            throw conflict(caseId, "the evidence bundle does not belong to the case at the frozen version");
        }
        DraftRevision revision = draftRevisions
                .findById(bundle.draftRevisionId())
                .orElseThrow(() -> conflict(caseId, "the evidence bundle sealed draft revision no longer exists"));
        if (!revision.invoiceCaseId().equals(caseId) || revision.status() != DraftRevisionStatus.SEALED) {
            throw conflict(caseId, "the evidence bundle source is not a sealed draft revision of the case");
        }
        List<InvoiceLine> lines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(revision.id());
        if (lines.isEmpty()) {
            throw conflict(caseId, "the sealed draft revision has no invoice lines");
        }
        EvidenceBundlePayloadHasher.CanonicalPayload canonical =
                bundleHasher.canonicalize(invoiceCase, revision.revisionNumber(), lines);
        if (!canonical.hash().equals(bundle.payloadHash())
                || !canonicalEquals(canonical.json(), bundle.payload())) {
            throw conflict(caseId, "the evidence bundle payload does not match its authoritative sealed lines");
        }
        return bundle;
    }

    private void verifyMatchResult(
            UUID caseId,
            ReviewSnapshot snapshot,
            MatchResult storedResult,
            MatchComputation computation,
            int mappingWatermark,
            long purchasingSnapshotVersion,
            String purchasingSnapshotHash) {
        if (!storedResult.invoiceCaseId().equals(caseId)
                || !storedResult.evidenceBundleId().equals(snapshot.evidenceBundleId())
                || !storedResult.id().equals(snapshot.matchResultId())
                || storedResult.resultNumber() != snapshot.matchResultNumber()) {
            throw conflict(caseId, "the stored match result does not match the review snapshot source");
        }
        if (!computation.resultHash().equals(storedResult.resultHash())
                || !canonicalEquals(computation.canonicalJson(), storedResult.payload())) {
            throw conflict(caseId, "the match result payload does not match an independent recomputation");
        }
        if (storedResult.purchasingSnapshotVersion() != purchasingSnapshotVersion
                || !storedResult.purchasingSnapshotHash().equals(purchasingSnapshotHash)
                || storedResult.mappingWatermark() != mappingWatermark) {
            throw conflict(caseId, "the match result source columns do not match the verified sources");
        }
    }

    private void verifyReviewSnapshot(
            InvoiceCase invoiceCase,
            ReviewSnapshot snapshot,
            EvidenceBundle bundle,
            MatchResult storedResult,
            List<com.invoicematch.core.matching.application.AppliedMapping> appliedMappings,
            List<EvidenceBundlePayload.EvidenceLine> evidenceLines,
            PurchaseOrderAggregate purchasing,
            long purchasingSnapshotVersion,
            String purchasingSnapshotHash) {
        UUID caseId = invoiceCase.id().value();
        if (snapshot.targetCaseVersion() != invoiceCase.version()
                || snapshot.targetEvidenceBundleVersion() != bundle.versionNumber()
                || !snapshot.evidenceBundleId().equals(bundle.id())
                || !snapshot.matchResultId().equals(storedResult.id())
                || snapshot.matchResultNumber() == null
                || snapshot.matchResultNumber() != storedResult.resultNumber()) {
            throw conflict(caseId, "the review snapshot relational source fields do not match the verified sources");
        }

        ReviewSnapshotPayloadInput input = new ReviewSnapshotPayloadInput(
                caseId,
                invoiceCase.version(),
                bundle.id(),
                bundle.versionNumber(),
                bundle.payloadHash(),
                storedResult.id(),
                storedResult.resultNumber(),
                storedResult.resultHash(),
                storedResult.mappingWatermark(),
                storedResult.payload(),
                appliedMappings,
                evidenceLines,
                purchasingSnapshotVersion,
                purchasing.purchaseOrder().version(),
                purchasingSnapshotHash);
        ReviewSnapshotPayloadBuilder.CanonicalPayload canonical = snapshotPayloadBuilder.canonicalize(input);
        if (!canonical.hash().equals(snapshot.payloadHash())
                || !canonicalEquals(canonical.json(), snapshot.payload())) {
            throw conflict(caseId, "the review snapshot payload does not match its authoritative sources");
        }
        if (snapshot.purchasingSnapshotVersion() != purchasingSnapshotVersion
                || !snapshot.purchasingSnapshotHash().equals(purchasingSnapshotHash)) {
            throw conflict(caseId, "the review snapshot purchasing source does not match the verified sources");
        }
    }

    /**
     * Semantic canonical comparison. Object key order is normalized (PostgreSQL
     * {@code jsonb} does not preserve it) and array order is preserved, so the
     * canonical JSON and the stored {@code jsonb} text compare equal when they
     * carry the same fields and scalar values.
     */
    private boolean canonicalEquals(String canonicalJson, String storedJson) {
        if (canonicalJson == null || storedJson == null) {
            return false;
        }
        try {
            return normalize(mapper.readTree(canonicalJson)).equals(normalize(mapper.readTree(storedJson)));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, normalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                ordered.add(normalize(element));
            }
            return ordered;
        }
        return node.deepCopy();
    }

    private static EvidenceBundlePayload.EvidenceLine toEvidenceLine(InvoiceLine line) {
        return new EvidenceBundlePayload.EvidenceLine(
                line.lineNumber(),
                line.rawItemName(),
                line.quantity().value(),
                line.unitPrice().amount(),
                line.confirmedItemId());
    }

    private static ReviewStateConflictException conflict(UUID caseId, String message) {
        return new ReviewStateConflictException(caseId, message);
    }
}
