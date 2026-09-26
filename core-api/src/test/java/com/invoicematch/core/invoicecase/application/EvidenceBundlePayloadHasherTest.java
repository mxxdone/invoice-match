package com.invoicematch.core.invoicecase.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseId;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceBundlePayloadHasherTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final EvidenceBundlePayloadHasher hasher = new EvidenceBundlePayloadHasher();

    @Test
    void canonicalHashIsIndependentOfLineOrdering() {
        InvoiceCase invoiceCase = invoiceCase();
        InvoiceLine first = line(invoiceCase, 1, "A4 Paper", 3, 2500, null);
        InvoiceLine second = line(invoiceCase, 2, "Toner", 1, 55000, "ITEM-TONER-BK");

        EvidenceBundlePayloadHasher.CanonicalPayload ascending =
                hasher.canonicalize(invoiceCase, 1, List.of(first, second));
        EvidenceBundlePayloadHasher.CanonicalPayload descending =
                hasher.canonicalize(invoiceCase, 1, List.of(second, first));

        assertThat(ascending.json()).isEqualTo(descending.json());
        assertThat(ascending.hash()).isEqualTo(descending.hash());
    }

    @Test
    void hashChangesWhenContentChanges() {
        InvoiceCase invoiceCase = invoiceCase();
        var before = hasher.canonicalize(invoiceCase, 1, List.of(line(invoiceCase, 1, "A4 Paper", 3, 2500, null)));
        var after = hasher.canonicalize(invoiceCase, 1, List.of(line(invoiceCase, 1, "A4 Paper", 4, 2500, null)));

        assertThat(after.hash()).isNotEqualTo(before.hash());
    }

    @Test
    void parseRoundTripsCanonicalPayload() {
        InvoiceCase invoiceCase = invoiceCase();
        InvoiceLine line = line(invoiceCase, 1, "A4 Paper", 3, 2500, "ITEM-A4-80");

        EvidenceBundlePayloadHasher.CanonicalPayload canonical =
                hasher.canonicalize(invoiceCase, 2, List.of(line));
        EvidenceBundlePayload payload = hasher.parse(canonical.json());

        assertThat(payload.caseId()).isEqualTo(invoiceCase.id().value().toString());
        assertThat(payload.supplierId()).isEqualTo("SUP-1");
        assertThat(payload.purchaseOrderId()).isEqualTo("PO-1");
        assertThat(payload.invoiceNumber()).isEqualTo("INV-001");
        assertThat(payload.revisionNumber()).isEqualTo(2);
        assertThat(payload.lines()).hasSize(1);
        assertThat(payload.lines().get(0).confirmedItemId()).isEqualTo("ITEM-A4-80");
    }

    private static InvoiceCase invoiceCase() {
        return InvoiceCase.create(
                InvoiceCaseId.newId(), SupplierId.of("SUP-1"), PurchaseOrderId.of("PO-1"), "INV-001", "INV001", T0);
    }

    private static InvoiceLine line(
            InvoiceCase invoiceCase, int lineNumber, String name, int quantity, long unitPrice, String confirmedItemId) {
        return InvoiceLine.create(
                UUID.randomUUID(),
                invoiceCase.id().value(),
                UUID.randomUUID(),
                lineNumber,
                name,
                Quantity.of(quantity),
                Money.of(unitPrice),
                confirmedItemId,
                T0);
    }
}
