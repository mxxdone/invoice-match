package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Full read model of one invoice case: its immutable header, optimistic
 * version, current editable revision (if any) and that revision's lines.
 */
public record InvoiceCaseDetail(
        UUID id,
        String supplierId,
        String purchaseOrderId,
        String invoiceNumber,
        String submittedBy,
        InvoiceCaseStatus status,
        long version,
        DraftRevisionDetail currentRevision,
        List<InvoiceLineDetail> lines) {

    public static InvoiceCaseDetail from(InvoiceCase invoiceCase, DraftRevision currentRevision, List<InvoiceLine> lines) {
        DraftRevisionDetail revision = currentRevision == null ? null : DraftRevisionDetail.from(currentRevision);
        List<InvoiceLineDetail> lineDetails = lines.stream()
                .sorted(Comparator.comparingInt(InvoiceLine::lineNumber))
                .map(InvoiceLineDetail::from)
                .toList();
        return new InvoiceCaseDetail(
                invoiceCase.id().value(),
                invoiceCase.supplier().value(),
                invoiceCase.purchaseOrder().value(),
                invoiceCase.invoiceNumber(),
                invoiceCase.submittedBy(),
                invoiceCase.status(),
                invoiceCase.version(),
                revision,
                lineDetails);
    }
}
