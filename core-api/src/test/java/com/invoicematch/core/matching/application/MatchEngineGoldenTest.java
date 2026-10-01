package com.invoicematch.core.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderStatus;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden regression for the canonical match payload. The expected bytes and hash
 * were captured from the pre-refactor engine, so any accidental change to JSON
 * key order, numeric width, receipt ordering or the hashing algorithm fails
 * here. The serialization lives in {@link MatchResultPayloadEncoder}; the
 * calculation lives in {@link MatchEngine}.
 */
class MatchEngineGoldenTest {

    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BUNDLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String GOLDEN_HASH =
            "30fbe4f187ef2a7c855d0b40420d4c48e9f82534e9f1c5c5e117e88de8040c92";

    private static final String GOLDEN_JSON = "{\"schemaVersion\":\"match-result-v3\","
            + "\"caseId\":\"11111111-1111-1111-1111-111111111111\",\"supplierId\":\"SUP-1\","
            + "\"purchaseOrderId\":\"PO-1001\",\"invoiceNumber\":\"INV-1\",\"normalizedInvoiceNumber\":\"INV1\","
            + "\"caseVersion\":3,\"evidenceBundle\":{\"id\":\"22222222-2222-2222-2222-222222222222\","
            + "\"version\":1,\"payloadHash\":\"bundle-hash\"},"
            + "\"appliedMappings\":[{\"lineNumber\":1,\"itemId\":\"ITEM-A\",\"purchaseOrderLineId\":\"POL-1\"}],"
            + "\"purchasingSnapshot\":{\"snapshotVersion\":5,\"purchaseOrderVersion\":3,"
            + "\"payloadHash\":\"snapshot-hash\",\"receipts\":["
            + "{\"receiptId\":\"R-A\",\"status\":\"CONFIRMED\",\"receiptDate\":\"2026-01-05\",\"version\":2,"
            + "\"lines\":[{\"receiptLineId\":\"RL-1\",\"version\":1,\"purchaseOrderLineId\":\"POL-1\","
            + "\"confirmedQuantity\":60}]},{\"receiptId\":\"R-B\",\"status\":\"CONFIRMED\","
            + "\"receiptDate\":\"2026-01-06\",\"version\":2,\"lines\":[{\"receiptLineId\":\"RL-2\",\"version\":1,"
            + "\"purchaseOrderLineId\":\"POL-1\",\"confirmedQuantity\":30}]}]},"
            + "\"allocationPlan\":{\"consuming\":false,\"mode\":\"NON_CONSUMING_EXPECTED_PLAN_V1\","
            + "\"fifoOrdering\":\"receiptDate,receiptLineId,receiptId\"},"
            + "\"lineOutcomes\":[{\"lineNumber\":1,\"rawItemName\":\"Premium Copy Paper A4\","
            + "\"confirmedItemId\":\"ITEM-A\",\"status\":\"MATCHED\",\"candidatePoLineIds\":[\"POL-1\"],"
            + "\"purchaseOrderLine\":{\"purchaseOrderLineId\":\"POL-1\",\"itemId\":\"ITEM-A\","
            + "\"orderedQuantity\":100,\"unitPrice\":2500},\"invoiceQuantity\":80,\"invoiceUnitPrice\":2500,"
            + "\"availableConfirmedQuantity\":90,\"plannedQuantity\":80,\"expectedAllocationPlan\":["
            + "{\"receiptId\":\"R-A\",\"receiptLineId\":\"RL-1\",\"receiptDate\":\"2026-01-05\","
            + "\"receiptLineVersion\":1,\"confirmedQuantity\":60,\"plannedQuantity\":60},"
            + "{\"receiptId\":\"R-B\",\"receiptLineId\":\"RL-2\",\"receiptDate\":\"2026-01-06\","
            + "\"receiptLineVersion\":1,\"confirmedQuantity\":30,\"plannedQuantity\":20}],\"exceptions\":[]}],"
            + "\"exceptions\":[{\"type\":\"DUPLICATE_INVOICE_SUSPECTED\",\"lineNumber\":null,"
            + "\"details\":{\"normalizedInvoiceNumber\":\"INV1\","
            + "\"otherCaseIds\":[\"aaaaaaaa-0000-0000-0000-000000000001\"]}}],\"normal\":false}";

    @Test
    void canonicalPayloadAndHashMatchThePreRefactorBaseline() {
        MatchComputation computation = new MatchEngine().compute(goldenInput());

        assertThat(computation.resultHash()).isEqualTo(GOLDEN_HASH);
        assertThat(computation.canonicalJson()).isEqualTo(GOLDEN_JSON);
        assertThat(computation.normal()).isFalse();
    }

    private static MatchInput goldenInput() {
        EvidenceBundlePayload.EvidenceLine invoice = new EvidenceBundlePayload.EvidenceLine(
                1, "Premium Copy Paper A4", 80, 2500, "ITEM-A");
        PurchaseOrderFacts po = new PurchaseOrderFacts(
                3,
                PurchaseOrderStatus.CONFIRMED,
                SupplierId.of("SUP-1"),
                "Hanul Office Supply",
                List.of(new PurchaseOrderLineFacts(
                        "POL-1", "ITEM-A", "A4 Paper", Quantity.of(100), Money.of(2500))));
        List<ReceiptFacts> receipts = List.of(
                new ReceiptFacts(
                        "R-B",
                        ReceiptStatus.CONFIRMED,
                        LocalDate.parse("2026-01-06"),
                        2,
                        List.of(new ReceiptLineFacts("RL-2", 1, "POL-1", ConfirmedQuantity.of(30)))),
                new ReceiptFacts(
                        "R-A",
                        ReceiptStatus.CONFIRMED,
                        LocalDate.parse("2026-01-05"),
                        2,
                        List.of(new ReceiptLineFacts("RL-1", 1, "POL-1", ConfirmedQuantity.of(60)))));
        PurchaseOrderAggregate aggregate =
                new PurchaseOrderAggregate(PurchaseOrderId.of("PO-1001"), 5, po, receipts);
        return new MatchInput(
                CASE_ID,
                "SUP-1",
                "PO-1001",
                "INV-1",
                "INV1",
                3,
                BUNDLE_ID,
                1,
                "bundle-hash",
                List.of(invoice),
                aggregate,
                "snapshot-hash",
                List.of(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")),
                List.of(new AppliedMapping(1, "ITEM-A", "POL-1")));
    }
}
