package com.invoicematch.core.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure unit tests of the deterministic matching engine. No Spring, no database:
 * the engine only sees semantic inputs.
 */
class MatchEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BUNDLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final MatchEngine engine = new MatchEngine();

    @Test
    void normalMatchIsExactlyZeroToleranceAndFullyPlanned() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 60, 2500, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 60))),
                List.of());

        JsonNode payload = compute(input);

        assertThat(payload.get("normal").asBoolean()).isTrue();
        assertThat(payload.get("exceptions")).isEmpty();
        JsonNode line = payload.get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("MATCHED");
        assertThat(line.get("availableConfirmedQuantity").asInt()).isEqualTo(60);
        assertThat(line.get("plannedQuantity").asInt()).isEqualTo(60);
        assertThat(line.get("expectedAllocationPlan")).hasSize(1);
        assertThat(payload.get("allocationPlan").get("consuming").asBoolean()).isFalse();
    }

    @Test
    void quantityAboveAvailableIsExceptionAndPlanStopsAtAvailable() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 100, 2500, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 60))),
                List.of());

        JsonNode payload = compute(input);

        assertThat(payload.get("normal").asBoolean()).isFalse();
        JsonNode exception = payload.get("exceptions").get(0);
        assertThat(exception.get("type").asText()).isEqualTo("QUANTITY_EXCEEDS_RECEIPT_BALANCE");
        assertThat(exception.get("details").get("invoiceQuantity").asInt()).isEqualTo(100);
        assertThat(exception.get("details").get("availableConfirmedQuantity").asInt()).isEqualTo(60);
        JsonNode line = payload.get("lineOutcomes").get(0);
        assertThat(line.get("plannedQuantity").asInt()).isEqualTo(60);
        assertThat(line.get("expectedAllocationPlan").get(0).get("plannedQuantity").asInt())
                .isEqualTo(60);
    }

    @Test
    void oneWonPriceDifferenceIsUnitPriceMismatch() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 10, 2501, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 100))),
                List.of());

        JsonNode payload = compute(input);

        assertThat(payload.get("normal").asBoolean()).isFalse();
        JsonNode exception = payload.get("exceptions").get(0);
        assertThat(exception.get("type").asText()).isEqualTo("UNIT_PRICE_MISMATCH");
        assertThat(exception.get("details").get("invoiceUnitPrice").asLong()).isEqualTo(2501);
        assertThat(exception.get("details").get("purchaseOrderUnitPrice").asLong()).isEqualTo(2500);
        assertThat(payload.get("lineOutcomes").get(0).get("plannedQuantity").asInt())
                .isEqualTo(10);
    }

    @Test
    void blankConfirmedItemIsUnconfirmedWithoutInventedMapping() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 10, 2500, "  ")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 100))),
                List.of());

        JsonNode payload = compute(input);

        JsonNode line = payload.get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("ITEM_UNCONFIRMED");
        assertThat(line.get("confirmedItemId").isNull()).isTrue();
        assertThat(line.get("candidatePoLineIds")).isEmpty();
        assertThat(line.get("plannedQuantity").asInt()).isZero();
        assertThat(payload.get("exceptions").get(0).get("type").asText()).isEqualTo("ITEM_UNCONFIRMED");
    }

    @Test
    void zeroCandidatePurchaseOrderLinesIsEvidenceInsufficient() {
        MatchInput input = input(
                List.of(invoice(1, "Toner", 10, 55000, "ITEM-MISSING")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(),
                List.of());

        JsonNode payload = compute(input);

        JsonNode line = payload.get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(line.get("candidatePoLineIds")).isEmpty();
        JsonNode exception = payload.get("exceptions").get(0);
        assertThat(exception.get("type").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(exception.get("details").get("candidatePoLineIds")).isEmpty();
    }

    @Test
    void multipleCandidatePurchaseOrderLinesIsEvidenceInsufficientAndCandidatesAreSorted() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 10, 2500, "ITEM-A")),
                List.of(
                        poLine("POL-2", "ITEM-A", 5, 2500),
                        poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(),
                List.of());

        JsonNode payload = compute(input);

        JsonNode line = payload.get("lineOutcomes").get(0);
        assertThat(line.get("status").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(line.get("candidatePoLineIds").get(0).asText()).isEqualTo("POL-1");
        assertThat(line.get("candidatePoLineIds").get(1).asText()).isEqualTo("POL-2");
    }

    @Test
    void duplicateBusinessInvoiceIsCaseLevelExceptionWithSortedOtherCaseIds() {
        UUID first = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 10, 2500, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 100))),
                List.of(second, first));

        JsonNode payload = compute(input);

        assertThat(payload.get("normal").asBoolean()).isFalse();
        JsonNode exception = payload.get("exceptions").get(0);
        assertThat(exception.get("type").asText()).isEqualTo("DUPLICATE_INVOICE_SUSPECTED");
        assertThat(exception.get("lineNumber").isNull()).isTrue();
        assertThat(exception.get("details").get("otherCaseIds").get(0).asText())
                .isEqualTo(first.toString());
        assertThat(exception.get("details").get("otherCaseIds").get(1).asText())
                .isEqualTo(second.toString());
    }

    @Test
    void multipleExceptionsCanCoexistOnOneLineAndAcrossLines() {
        MatchInput input = input(
                List.of(
                        invoice(1, "A4 Paper", 100, 2501, "ITEM-A"),
                        invoice(2, "Toner", 5, 55000, null)),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500), poLine("POL-2", "ITEM-B", 20, 55000)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 60))),
                List.of(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003")));

        JsonNode payload = compute(input);

        List<String> types = new ArrayList<>();
        payload.get("exceptions").forEach(node -> types.add(node.get("type").asText()));
        assertThat(types).containsExactly(
                "QUANTITY_EXCEEDS_RECEIPT_BALANCE",
                "UNIT_PRICE_MISMATCH",
                "ITEM_UNCONFIRMED",
                "DUPLICATE_INVOICE_SUSPECTED");
        JsonNode firstLine = payload.get("lineOutcomes").get(0);
        assertThat(firstLine.get("exceptions")).hasSize(2);
    }

    @Test
    void fifoSplitsAcrossReceiptLinesOrderedByDateThenReceiptLineIdThenReceiptId() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 80, 2500, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(
                        receipt("R-B", "2026-01-06", 2, rline("RL-2", 1, "POL-1", 30)),
                        receipt("R-A", "2026-01-05", 2, rline("RL-9", 1, "POL-1", 20)),
                        receipt("R-C", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 50))),
                List.of());

        JsonNode payload = compute(input);

        JsonNode plan = payload.get("lineOutcomes").get(0).get("expectedAllocationPlan");
        assertThat(plan).hasSize(3);
        assertThat(plan.get(0).get("receiptId").asText()).isEqualTo("R-C");
        assertThat(plan.get(0).get("plannedQuantity").asInt()).isEqualTo(50);
        assertThat(plan.get(1).get("receiptId").asText()).isEqualTo("R-A");
        assertThat(plan.get(1).get("plannedQuantity").asInt()).isEqualTo(20);
        assertThat(plan.get(2).get("receiptId").asText()).isEqualTo("R-B");
        assertThat(plan.get(2).get("plannedQuantity").asInt()).isEqualTo(10);
        assertThat(payload.get("lineOutcomes").get(0).get("plannedQuantity").asInt())
                .isEqualTo(80);
    }

    @Test
    void unconfirmedReceiptsAndZeroQuantitiesAreExcludedFromAvailable() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 10, 2500, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(
                        receipt("R-UNCONFIRMED", "2026-01-04", 1, ReceiptStatus.UNCONFIRMED,
                                rline("RL-X", 1, "POL-1", 999)),
                        receipt("R-ZERO", "2026-01-05", 1, rline("RL-Z", 1, "POL-1", 0))),
                List.of());

        JsonNode payload = compute(input);

        JsonNode line = payload.get("lineOutcomes").get(0);
        assertThat(line.get("availableConfirmedQuantity").asInt()).isZero();
        assertThat(line.get("expectedAllocationPlan")).isEmpty();
        assertThat(payload.get("exceptions").get(0).get("type").asText())
                .isEqualTo("QUANTITY_EXCEEDS_RECEIPT_BALANCE");
    }

    @ParameterizedTest
    @CsvSource({"1,2500", "59,2500", "60,2500", "61,2500", "100,2500", "60,2499", "60,2501"})
    void boundaryQuantitiesAndPrices(int invoiceQuantity, long unitPrice) {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", invoiceQuantity, unitPrice, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 60))),
                List.of());

        JsonNode line = compute(input).get("lineOutcomes").get(0);
        int planned = line.get("plannedQuantity").asInt();
        assertThat(planned).isLessThanOrEqualTo(invoiceQuantity);
        assertThat(planned).isLessThanOrEqualTo(60);
        assertThat(line.get("expectedAllocationPlan").findValues("plannedQuantity").stream()
                        .mapToInt(JsonNode::asInt)
                        .sum())
                .isEqualTo(planned);
    }

    @Test
    void shuffledRepositoryOrderProducesIdenticalCanonicalJsonAndHash() {
        List<EvidenceBundlePayload.EvidenceLine> invoiceLines = List.of(
                invoice(2, "Toner", 20, 55000, "ITEM-B"),
                invoice(1, "A4 Paper", 80, 2500, "ITEM-A"));
        List<PurchaseOrderLineFacts> poLines = List.of(
                poLine("POL-2", "ITEM-B", 20, 55000),
                poLine("POL-1", "ITEM-A", 100, 2500));
        List<ReceiptFacts> receipts = List.of(
                receipt("R-B", "2026-01-06", 2, rline("RL-2", 1, "POL-1", 30)),
                receipt("R-A", "2026-01-05", 2, rline("RL-9", 1, "POL-1", 20)),
                receipt("R-C", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 50)));
        List<UUID> duplicates = List.of(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"),
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));

        MatchComputation first = engine.compute(input(invoiceLines, poLines, receipts, duplicates));

        List<EvidenceBundlePayload.EvidenceLine> shuffledInvoice = new ArrayList<>(invoiceLines);
        Collections.reverse(shuffledInvoice);
        List<PurchaseOrderLineFacts> shuffledPo = new ArrayList<>(poLines);
        Collections.reverse(shuffledPo);
        List<ReceiptFacts> shuffledReceipts = new ArrayList<>(receipts);
        Collections.reverse(shuffledReceipts);
        List<UUID> shuffledDuplicates = new ArrayList<>(duplicates);
        Collections.reverse(shuffledDuplicates);

        MatchComputation second =
                engine.compute(input(shuffledInvoice, shuffledPo, shuffledReceipts, shuffledDuplicates));

        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(second.resultHash()).isEqualTo(first.resultHash());
    }

    @Test
    void randomizedPlansStayWithinInvoiceAndReceiptQuantities() throws Exception {
        Random random = new Random(20260926L);
        for (int iteration = 0; iteration < 300; iteration++) {
            int receiptCount = 1 + random.nextInt(4);
            List<ReceiptFacts> receipts = new ArrayList<>();
            int quotedAvailable = 0;
            for (int i = 0; i < receiptCount; i++) {
                int confirmed = random.nextInt(51);
                quotedAvailable += confirmed;
                receipts.add(receipt(
                        "R-" + i,
                        LocalDate.of(2026, 1, 1).plusDays(random.nextInt(5)).toString(),
                        1,
                        rline("RL-" + i, 1, "POL-1", confirmed)));
            }
            int invoiceQuantity = 1 + random.nextInt(150);
            MatchInput input = input(
                    List.of(invoice(1, "A4 Paper", invoiceQuantity, 2500, "ITEM-A")),
                    List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                    receipts,
                    List.of());

            JsonNode line = compute(input).get("lineOutcomes").get(0);
            if (line.get("status").asText().equals("EVIDENCE_INSUFFICIENT")) {
                continue;
            }
            int planned = line.get("plannedQuantity").asInt();
            assertThat(planned).isLessThanOrEqualTo(invoiceQuantity);
            int plannedSum = 0;
            for (JsonNode allocation : line.get("expectedAllocationPlan")) {
                int plannedHere = allocation.get("plannedQuantity").asInt();
                assertThat(plannedHere).isPositive();
                assertThat(plannedHere)
                        .isLessThanOrEqualTo(allocation.get("confirmedQuantity").asInt());
                plannedSum += plannedHere;
            }
            assertThat(plannedSum).isEqualTo(planned);
            if (quotedAvailable >= invoiceQuantity) {
                assertThat(planned).isEqualTo(invoiceQuantity);
            }
        }
    }

    @Test
    void twoInvoiceLinesSharingOneReceiptLineCannotOverbook() {
        MatchInput input = input(
                List.of(
                        invoice(1, "A4 Paper", 40, 2500, "ITEM-A"),
                        invoice(2, "A4 Paper", 40, 2500, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(receipt("R-1", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 60))),
                List.of());

        JsonNode payload = compute(input);

        JsonNode first = payload.get("lineOutcomes").get(0);
        JsonNode second = payload.get("lineOutcomes").get(1);
        assertThat(first.get("availableConfirmedQuantity").asLong()).isEqualTo(60L);
        assertThat(first.get("plannedQuantity").asLong()).isEqualTo(40L);
        assertThat(first.get("exceptions")).isEmpty();
        assertThat(second.get("availableConfirmedQuantity").asLong()).isEqualTo(20L);
        assertThat(second.get("plannedQuantity").asLong()).isEqualTo(20L);
        assertThat(second.get("exceptions").get(0).get("type").asText())
                .isEqualTo("QUANTITY_EXCEEDS_RECEIPT_BALANCE");

        assertThat(totalPlannedForReceiptLine(payload, "RL-1")).isLessThanOrEqualTo(60L);
        assertThat(payload.get("normal").asBoolean()).isFalse();
    }

    @Test
    void aggregateAvailabilityIsLongAndDoesNotWrap() {
        MatchInput input = input(
                List.of(invoice(1, "A4 Paper", 5, 2500, "ITEM-A")),
                List.of(poLine("POL-1", "ITEM-A", 100, 2500)),
                List.of(
                        receipt("R-1", "2026-01-05", 1, rline("RL-1", 1, "POL-1", Integer.MAX_VALUE)),
                        receipt("R-2", "2026-01-06", 1, rline("RL-2", 1, "POL-1", Integer.MAX_VALUE))),
                List.of());

        JsonNode line = compute(input).get("lineOutcomes").get(0);

        assertThat(line.get("availableConfirmedQuantity").asLong())
                .isEqualTo(2L * Integer.MAX_VALUE);
        assertThat(line.get("plannedQuantity").asLong()).isEqualTo(5L);
        assertThat(line.get("expectedAllocationPlan").get(0).get("plannedQuantity").asInt())
                .isEqualTo(5);
    }

    @Test
    void multiLineShuffledOrderStillProducesIdenticalCanonicalJsonAndHash() {
        List<EvidenceBundlePayload.EvidenceLine> invoiceLines = List.of(
                invoice(1, "A4 Paper", 40, 2500, "ITEM-A"),
                invoice(2, "A4 Paper", 30, 2500, "ITEM-A"),
                invoice(3, "A4 Paper", 50, 2600, "ITEM-A"));
        List<PurchaseOrderLineFacts> poLines = List.of(poLine("POL-1", "ITEM-A", 100, 2500));
        List<ReceiptFacts> receipts = List.of(
                receipt("R-B", "2026-01-06", 2, rline("RL-2", 1, "POL-1", 30)),
                receipt("R-A", "2026-01-05", 2, rline("RL-1", 1, "POL-1", 20)));

        MatchComputation first = engine.compute(input(invoiceLines, poLines, receipts, List.of()));

        List<EvidenceBundlePayload.EvidenceLine> shuffledInvoice = new ArrayList<>(invoiceLines);
        Collections.reverse(shuffledInvoice);
        List<ReceiptFacts> shuffledReceipts = new ArrayList<>(receipts);
        Collections.reverse(shuffledReceipts);

        MatchComputation second = engine.compute(input(shuffledInvoice, poLines, shuffledReceipts, List.of()));

        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(second.resultHash()).isEqualTo(first.resultHash());
    }

    @Test
    void randomizedMultiLinePlansNeverExceedAnyReceiptConfirmedQuantity() throws Exception {
        Random random = new Random(20260926L);
        for (int iteration = 0; iteration < 200; iteration++) {
            int lineCount = 1 + random.nextInt(4);
            List<EvidenceBundlePayload.EvidenceLine> invoiceLines = new ArrayList<>();
            for (int i = 0; i < lineCount; i++) {
                invoiceLines.add(invoice(i + 1, "A4 Paper", 1 + random.nextInt(80), 2500, "ITEM-A"));
            }
            int receiptCount = 1 + random.nextInt(4);
            List<ReceiptFacts> receipts = new ArrayList<>();
            Map<String, Integer> confirmedByReceiptLine = new HashMap<>();
            for (int i = 0; i < receiptCount; i++) {
                int confirmed = random.nextInt(51);
                String receiptLineId = "RL-" + i;
                confirmedByReceiptLine.put(receiptLineId, confirmed);
                receipts.add(receipt(
                        "R-" + i,
                        LocalDate.of(2026, 1, 1).plusDays(random.nextInt(5)).toString(),
                        1,
                        rline(receiptLineId, 1, "POL-1", confirmed)));
            }
            Collections.shuffle(invoiceLines, random);
            Collections.shuffle(receipts, random);

            JsonNode payload = compute(input(invoiceLines, List.of(poLine("POL-1", "ITEM-A", 100, 2500)), receipts,
                    List.of()));

            Map<String, Long> plannedByReceiptLine = new HashMap<>();
            for (JsonNode line : payload.get("lineOutcomes")) {
                long planned = line.get("plannedQuantity").asLong();
                long plannedSum = 0L;
                for (JsonNode allocation : line.get("expectedAllocationPlan")) {
                    String receiptLineId = allocation.get("receiptLineId").asText();
                    long plannedHere = allocation.get("plannedQuantity").asLong();
                    assertThat(plannedHere).isPositive();
                    assertThat(plannedHere)
                            .isLessThanOrEqualTo(allocation.get("confirmedQuantity").asLong());
                    plannedSum += plannedHere;
                    plannedByReceiptLine.merge(receiptLineId, plannedHere, Long::sum);
                }
                assertThat(plannedSum).isEqualTo(planned);
                assertThat(planned).isLessThanOrEqualTo(line.get("availableConfirmedQuantity").asLong());
            }
            for (Map.Entry<String, Long> entry : plannedByReceiptLine.entrySet()) {
                assertThat(entry.getValue())
                        .isLessThanOrEqualTo((long) confirmedByReceiptLine.get(entry.getKey()));
            }
        }
    }

    private static long totalPlannedForReceiptLine(JsonNode payload, String receiptLineId) {
        long total = 0L;
        for (JsonNode line : payload.get("lineOutcomes")) {
            for (JsonNode allocation : line.get("expectedAllocationPlan")) {
                if (allocation.get("receiptLineId").asText().equals(receiptLineId)) {
                    total += allocation.get("plannedQuantity").asLong();
                }
            }
        }
        return total;
    }

    private JsonNode compute(MatchInput input) {
        MatchComputation computation = engine.compute(input);
        try {
            return MAPPER.readTree(computation.canonicalJson());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MatchInput input(
            List<EvidenceBundlePayload.EvidenceLine> invoiceLines,
            List<PurchaseOrderLineFacts> poLines,
            List<ReceiptFacts> receipts,
            List<UUID> duplicateCaseIds) {
        PurchaseOrderFacts purchaseOrder = new PurchaseOrderFacts(
                3, PurchaseOrderStatus.CONFIRMED, SupplierId.of("SUP-1"), "Hanul Office Supply", poLines);
        PurchaseOrderAggregate aggregate =
                new PurchaseOrderAggregate(PurchaseOrderId.of("PO-1001"), 5, purchaseOrder, receipts);
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
                invoiceLines,
                aggregate,
                "snapshot-hash",
                duplicateCaseIds);
    }

    private static EvidenceBundlePayload.EvidenceLine invoice(
            int lineNumber, String rawItemName, int quantity, long unitPrice, String confirmedItemId) {
        return new EvidenceBundlePayload.EvidenceLine(
                lineNumber, rawItemName, quantity, unitPrice, confirmedItemId);
    }

    private static PurchaseOrderLineFacts poLine(String id, String itemId, int orderedQuantity, long unitPrice) {
        return new PurchaseOrderLineFacts(
                id, itemId, itemId + " name", Quantity.of(orderedQuantity), Money.of(unitPrice));
    }

    private static ReceiptFacts receipt(String id, String date, long version, ReceiptLineFacts... lines) {
        return receipt(id, date, version, ReceiptStatus.CONFIRMED, lines);
    }

    private static ReceiptFacts receipt(
            String id, String date, long version, ReceiptStatus status, ReceiptLineFacts... lines) {
        return new ReceiptFacts(id, status, LocalDate.parse(date), version, List.of(lines));
    }

    private static ReceiptLineFacts rline(String id, long version, String poLineId, int confirmedQuantity) {
        return new ReceiptLineFacts(id, version, poLineId, ConfirmedQuantity.of(confirmedQuantity));
    }
}
