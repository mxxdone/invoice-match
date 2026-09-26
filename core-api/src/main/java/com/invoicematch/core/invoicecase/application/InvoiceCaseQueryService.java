package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.CaseStateConflictException;
import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.DraftRevisionStatus;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.EvidenceBundleNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.invoicecase.persistence.DraftRevisionRepository;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceLineRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the invoice case flow: the case with its current editable draft
 * and every frozen evidence bundle version.
 */
@Service
@Transactional(readOnly = true)
public class InvoiceCaseQueryService {

    private final InvoiceCaseRepository invoiceCases;
    private final DraftRevisionRepository draftRevisions;
    private final InvoiceLineRepository invoiceLines;
    private final EvidenceBundleRepository evidenceBundles;

    public InvoiceCaseQueryService(
            InvoiceCaseRepository invoiceCases,
            DraftRevisionRepository draftRevisions,
            InvoiceLineRepository invoiceLines,
            EvidenceBundleRepository evidenceBundles) {
        this.invoiceCases = invoiceCases;
        this.draftRevisions = draftRevisions;
        this.invoiceLines = invoiceLines;
        this.evidenceBundles = evidenceBundles;
    }

    public InvoiceCaseDetail get(UUID caseId) {
        InvoiceCase invoiceCase =
                invoiceCases.findById(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
        DraftRevision draft = draftRevisions
                .findByInvoiceCaseIdAndStatus(caseId, DraftRevisionStatus.OPEN)
                .orElse(null);
        List<InvoiceLine> lines = draft == null
                ? List.of()
                : invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(draft.id());
        return InvoiceCaseDetail.from(invoiceCase, draft, lines);
    }

    public List<EvidenceBundleSummary> listEvidenceBundles(UUID caseId) {
        requireCase(caseId);
        return evidenceBundles.findByInvoiceCaseIdOrderByVersionNumberAsc(caseId).stream()
                .map(EvidenceBundleSummary::from)
                .toList();
    }

    public EvidenceBundleDetail getEvidenceBundle(UUID caseId, int versionNumber) {
        requireCase(caseId);
        EvidenceBundle bundle = evidenceBundles
                .findByInvoiceCaseIdAndVersionNumber(caseId, versionNumber)
                .orElseThrow(() -> new EvidenceBundleNotFoundException(caseId, versionNumber));
        return EvidenceBundleDetail.from(bundle);
    }

    /**
     * Loads the case header and its latest frozen evidence bundle for the
     * matching module while holding the invoice case row
     * {@code PESSIMISTIC_WRITE} lock. Taking the case lock before reading the
     * bundle is the same case-then-child ordering every P1-03 writer uses, so a
     * concurrent submit or state transition that changes the case version
     * cannot interleave between the state read and the bundle read. A case
     * without a frozen bundle cannot be matched, which is a state conflict
     * rather than a missing resource.
     */
    @Transactional
    public MatchCaseSnapshot loadForMatching(UUID caseId) {
        InvoiceCase invoiceCase =
                invoiceCases.findByIdForUpdate(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
        EvidenceBundle bundle = evidenceBundles
                .findFirstByInvoiceCaseIdOrderByVersionNumberDesc(caseId)
                .orElseThrow(() -> new CaseStateConflictException(
                        caseId, "no frozen evidence bundle exists to match"));
        return new MatchCaseSnapshot(
                invoiceCase.id().value(),
                invoiceCase.supplier().value(),
                invoiceCase.purchaseOrder().value(),
                invoiceCase.invoiceNumber(),
                invoiceCase.normalizedInvoiceNumber(),
                invoiceCase.status(),
                invoiceCase.version(),
                bundle.id(),
                bundle.versionNumber(),
                bundle.payloadHash(),
                bundle.payload());
    }

    /**
     * Identifiers of other cases that share this supplier and normalized invoice
     * number, for the {@code DUPLICATE_INVOICE_SUSPECTED} exception.
     */
    public List<UUID> findOtherCaseIdsWithBusinessInvoice(
            String supplierId, String normalizedInvoiceNumber, UUID excludingCaseId) {
        return invoiceCases.findOtherCaseIdsByBusinessInvoice(
                supplierId, normalizedInvoiceNumber, excludingCaseId);
    }

    /**
     * Throws {@link InvoiceCaseNotFoundException} when the case does not exist.
     */
    public void requireCase(UUID caseId) {
        if (!invoiceCases.existsById(caseId)) {
            throw new InvoiceCaseNotFoundException(caseId);
        }
    }
}
