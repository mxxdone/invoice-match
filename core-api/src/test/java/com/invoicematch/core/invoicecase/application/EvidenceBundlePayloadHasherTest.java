package com.invoicematch.core.invoicecase.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.document.domain.DocumentEvidence;
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
    private static final UUID FIXED_CASE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /**
     * Byte-for-byte golden of the document-less canonical payload. Any change to
     * this string or hash silently changes every already-issued manual bundle,
     * so it must stay frozen.
     */
    private static final String LEGACY_GOLDEN_JSON = "{\"caseId\":\"00000000-0000-0000-0000-0000000000aa\","
            + "\"supplierId\":\"SUP-1\",\"purchaseOrderId\":\"PO-1\",\"invoiceNumber\":\"INV-001\","
            + "\"revisionNumber\":1,\"lines\":[{\"lineNumber\":1,\"rawItemName\":\"A4 Paper\",\"quantity\":3,"
            + "\"unitPrice\":2500,\"confirmedItemId\":null}]}";
    private static final String LEGACY_GOLDEN_HASH =
            "9e1b697d61a5c65a940f234e992f74c5613499b9d16f5b0cc7f8659e678c5086";

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
        assertThat(payload.schemaVersion()).isNull();
        assertThat(payload.documents()).isNull();
    }

    @Test
    void documentLessPayloadKeepsTheFrozenLegacyBytesAndHash() {
        InvoiceCase invoiceCase = invoiceCase();
        InvoiceLine line = line(invoiceCase, 1, "A4 Paper", 3, 2500, null);

        EvidenceBundlePayloadHasher.CanonicalPayload canonical =
                hasher.canonicalize(invoiceCase, 1, List.of(line));

        assertThat(canonical.json()).isEqualTo(LEGACY_GOLDEN_JSON);
        assertThat(canonical.hash()).isEqualTo(LEGACY_GOLDEN_HASH);
    }

    @Test
    void documentPayloadFreezesSortedDocumentsWithSchemaVersionTwo() {
        InvoiceCase invoiceCase = invoiceCase();
        InvoiceLine line = line(invoiceCase, 1, "A4 Paper", 3, 2500, null);
        DocumentEvidence first = document(1, "a.pdf", DocumentEvidenceFixtures.PDF, 10, checksum('a'));
        DocumentEvidence second = document(2, "b.xlsx", DocumentEvidenceFixtures.XLSX, 20, checksum('b'));

        EvidenceBundlePayloadHasher.CanonicalPayload ascending =
                hasher.canonicalize(invoiceCase, 1, List.of(line), List.of(first, second));
        EvidenceBundlePayloadHasher.CanonicalPayload descending =
                hasher.canonicalize(invoiceCase, 1, List.of(line), List.of(second, first));

        assertThat(ascending.hash()).isEqualTo(descending.hash());
        assertThat(ascending.json()).contains("\"schemaVersion\":2");
        assertThat(ascending.json().indexOf(first.documentId().toString()))
                .isLessThan(ascending.json().indexOf(second.documentId().toString()));

        EvidenceBundlePayload payload = hasher.parse(ascending.json());
        assertThat(payload.schemaVersion()).isEqualTo(2);
        assertThat(payload.documents()).hasSize(2);
        assertThat(payload.documents().get(0).documentId()).isEqualTo(first.documentId().toString());
        assertThat(payload.documents().get(0).sourceDraftRevisionId())
                .isEqualTo(first.sourceDraftRevisionId().toString());
        assertThat(payload.documents().get(0).checksum()).isEqualTo(first.checksum());
    }

    @Test
    void documentMetadataChangeChangesTheHash() {
        InvoiceCase invoiceCase = invoiceCase();
        InvoiceLine line = line(invoiceCase, 1, "A4 Paper", 3, 2500, null);
        DocumentEvidence original = document(1, "a.pdf", DocumentEvidenceFixtures.PDF, 10, checksum('a'));

        var before = hasher.canonicalize(invoiceCase, 1, List.of(line), List.of(original));
        var renamed = hasher.canonicalize(invoiceCase, 1, List.of(line),
                List.of(document(1, "renamed.pdf", DocumentEvidenceFixtures.PDF, 10, checksum('a'))));
        var resized = hasher.canonicalize(invoiceCase, 1, List.of(line),
                List.of(document(1, "a.pdf", DocumentEvidenceFixtures.PDF, 11, checksum('a'))));
        var rechecksum = hasher.canonicalize(invoiceCase, 1, List.of(line),
                List.of(document(1, "a.pdf", DocumentEvidenceFixtures.PDF, 10, checksum('c'))));

        assertThat(renamed.hash()).isNotEqualTo(before.hash());
        assertThat(resized.hash()).isNotEqualTo(before.hash());
        assertThat(rechecksum.hash()).isNotEqualTo(before.hash());
    }

    private static final class DocumentEvidenceFixtures {
        private static final String PDF = "application/pdf";
        private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    private static DocumentEvidence document(
            int suffix, String fileName, String mediaType, long sizeBytes, String checksum) {
        return new DocumentEvidence(
                UUID.fromString("00000000-0000-0000-0000-00000000000" + suffix),
                UUID.fromString("00000000-0000-0000-0000-0000000000b" + suffix),
                fileName,
                mediaType,
                sizeBytes,
                checksum);
    }

    private static String checksum(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static InvoiceCase invoiceCase() {
        return InvoiceCase.create(
                InvoiceCaseId.of(FIXED_CASE), SupplierId.of("SUP-1"), PurchaseOrderId.of("PO-1"), "INV-001", "INV001",
                "submitter", T0);
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
