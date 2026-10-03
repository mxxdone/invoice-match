package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.persistence.AnalysisRunSnapshot;
import com.invoicematch.core.document.domain.DocumentEvidence;
import com.invoicematch.core.document.persistence.DocumentStore;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayloadHasher;
import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.DraftRevisionStatus;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.invoicecase.persistence.DraftRevisionRepository;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceLineRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Independently verifies the frozen manifest behind an analysis run before any
 * execution mutation: the persisted bundle must be the document-v2 payload of
 * the run's own evidence version, its JSON must carry schemaVersion 2 and the
 * frozen case/revision identity, its authoritative sealed revision must hold a
 * non-empty unique document set, and the stored canonical evidence hash must
 * equal a fresh recomputation with the existing
 * {@link EvidenceBundlePayloadHasher} algorithm.
 *
 * <p>This is the same authority the approval path reconstructs an evidence
 * bundle from. Java never trusts the stored {@code jsonb} text as canonical
 * (PostgreSQL does not preserve key order or formatting); it compares the
 * semantic tree. A mismatch is an {@link AnalysisConflictException} with no
 * side effect.
 */
@Component
public class AnalysisManifestVerifier {

    private final EvidenceBundleRepository evidenceBundles;
    private final InvoiceCaseRepository invoiceCases;
    private final DraftRevisionRepository draftRevisions;
    private final InvoiceLineRepository invoiceLines;
    private final DocumentStore documentEvidence;
    private final EvidenceBundlePayloadHasher bundleHasher;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalysisManifestVerifier(
            EvidenceBundleRepository evidenceBundles,
            InvoiceCaseRepository invoiceCases,
            DraftRevisionRepository draftRevisions,
            InvoiceLineRepository invoiceLines,
            DocumentStore documentEvidence,
            EvidenceBundlePayloadHasher bundleHasher) {
        this.evidenceBundles = evidenceBundles;
        this.invoiceCases = invoiceCases;
        this.draftRevisions = draftRevisions;
        this.invoiceLines = invoiceLines;
        this.documentEvidence = documentEvidence;
        this.bundleHasher = bundleHasher;
    }

    public List<DocumentEvidence> verify(AnalysisRunSnapshot run) {
        EvidenceBundle bundle = evidenceBundles.findById(run.evidenceBundleId())
                .orElseThrow(() -> conflict("the frozen evidence bundle no longer exists"));
        if (!bundle.invoiceCaseId().equals(run.invoiceCaseId())
                || bundle.versionNumber() != run.inputVersion()
                || !EvidenceBundlePayloadHasher.DOCUMENT_SCHEMA.equals(bundle.payloadSchema())
                || !bundle.payloadHash().equals(run.evidencePayloadHash())) {
            throw conflict("the frozen bundle version/hash/schema does not match the run identity");
        }

        InvoiceCase invoiceCase = invoiceCases.findById(run.invoiceCaseId())
                .orElseThrow(() -> conflict("the run case no longer exists"));
        DraftRevision revision = draftRevisions.findById(bundle.draftRevisionId())
                .orElseThrow(() -> conflict("the frozen bundle sealed draft revision no longer exists"));
        if (!revision.invoiceCaseId().equals(run.invoiceCaseId())
                || revision.status() != DraftRevisionStatus.SEALED) {
            throw conflict("the frozen bundle source is not a sealed draft revision of the run case");
        }

        EvidenceBundlePayload payload;
        try {
            payload = bundleHasher.parse(bundle.payload());
        } catch (IllegalStateException e) {
            throw conflict("the frozen evidence payload is not readable");
        }
        if (payload.schemaVersion() == null
                || payload.schemaVersion() != EvidenceBundlePayloadHasher.DOCUMENT_SCHEMA_VERSION
                || !run.invoiceCaseId().toString().equals(payload.caseId())
                || payload.revisionNumber() != revision.revisionNumber()) {
            throw conflict("the frozen bundle payload does not carry the document-v2 schema and case/revision identity");
        }

        List<InvoiceLine> sealedLines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(revision.id());
        if (sealedLines.isEmpty()) {
            throw conflict("the sealed draft revision has no invoice lines");
        }
        List<DocumentEvidence> documents = documentEvidence.evidenceForRevision(revision.id());
        if (documents.isEmpty()) {
            throw conflict("the frozen evidence bundle has no document references");
        }
        Set<UUID> seen = new HashSet<>();
        for (DocumentEvidence document : documents) {
            if (!seen.add(document.documentId())) {
                throw conflict("the frozen evidence bundle manifest is not unique");
            }
        }

        EvidenceBundlePayloadHasher.CanonicalPayload canonical =
                bundleHasher.canonicalize(invoiceCase, revision.revisionNumber(), sealedLines, documents);
        if (!canonical.hash().equals(bundle.payloadHash())
                || !canonicalEquals(canonical.json(), bundle.payload())) {
            throw conflict("the evidence bundle payload does not match its authoritative sealed evidence");
        }
        return documents;
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
            return mapper.readTree(canonicalJson).equals(mapper.readTree(storedJson));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private static AnalysisConflictException conflict(String message) {
        return new AnalysisConflictException("MANIFEST_MISMATCH", message);
    }
}
