package com.invoicematch.core.invoicecase.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private final RequestFingerprint fingerprint = new RequestFingerprint();

    @Test
    void replaceDraftFingerprintIsIndependentOfLineOrdering() {
        ReplaceDraftLinesCommand ascending = replaceDraft(List.of(
                new InvoiceLineInput(1, "A4 Paper", 3, 2500, null),
                new InvoiceLineInput(2, "Toner", 1, 55000, "ITEM-TONER-BK")));
        ReplaceDraftLinesCommand descending = replaceDraft(List.of(
                new InvoiceLineInput(2, "Toner", 1, 55000, "ITEM-TONER-BK"),
                new InvoiceLineInput(1, "A4 Paper", 3, 2500, null)));

        assertThat(fingerprint.replaceDraft(ascending)).isEqualTo(fingerprint.replaceDraft(descending));
    }

    @Test
    void replaceDraftFingerprintChangesWhenPayloadChanges() {
        ReplaceDraftLinesCommand before = replaceDraft(List.of(new InvoiceLineInput(1, "A4 Paper", 3, 2500, null)));
        ReplaceDraftLinesCommand after = replaceDraft(List.of(new InvoiceLineInput(1, "A4 Paper", 4, 2500, null)));

        assertThat(fingerprint.replaceDraft(after)).isNotEqualTo(fingerprint.replaceDraft(before));
    }

    @Test
    void createFingerprintDependsOnlyOnHeaderPayload() {
        CreateInvoiceCaseCommand first = new CreateInvoiceCaseCommand("req-1", "SUP-1", "PO-1", "INV-001");
        CreateInvoiceCaseCommand sameContentDifferentRequestId =
                new CreateInvoiceCaseCommand("req-2", "SUP-1", "PO-1", "INV-001");
        CreateInvoiceCaseCommand differentContent = new CreateInvoiceCaseCommand("req-1", "SUP-1", "PO-1", "INV-002");

        assertThat(fingerprint.create(sameContentDifferentRequestId)).isEqualTo(fingerprint.create(first));
        assertThat(fingerprint.create(differentContent)).isNotEqualTo(fingerprint.create(first));
    }

    private static ReplaceDraftLinesCommand replaceDraft(List<InvoiceLineInput> lines) {
        return new ReplaceDraftLinesCommand(java.util.UUID.randomUUID(), "req-1", 3L, lines);
    }
}
