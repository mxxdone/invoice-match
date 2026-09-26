package com.invoicematch.core.invoicecase.application;

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

    private void requireCase(UUID caseId) {
        if (!invoiceCases.existsById(caseId)) {
            throw new InvoiceCaseNotFoundException(caseId);
        }
    }
}
