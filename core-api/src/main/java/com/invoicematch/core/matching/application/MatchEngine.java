package com.invoicematch.core.matching.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.matching.domain.MatchException;
import com.invoicematch.core.matching.domain.MatchExceptionType;
import com.invoicematch.core.matching.domain.MatchLineOutcome;
import com.invoicematch.core.matching.domain.MatchLineStatus;
import com.invoicematch.core.matching.domain.MatchPoLine;
import com.invoicematch.core.matching.domain.PlannedAllocation;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Pure deterministic 3-way matching engine. It compares a frozen evidence
 * bundle against one current purchasing snapshot with zero tolerance and
 * returns a canonical JSON payload and its hash.
 *
 * <p>Every collection is explicitly ordered, so the same semantic inputs yield
 * byte-identical output independent of repository or list ordering. The
 * expected allocation plan is FIFO by receipt date, receipt line id and receipt
 * id; it is a non-consuming plan and creates no {@code ReceiptAllocation}.
 */
@Component
public class MatchEngine {

    /** Stable identifier of the canonical payload shape, bumped on any change. */
    public static final String SCHEMA_VERSION = "match-result-v2";

    private static final Comparator<EvidenceBundlePayload.EvidenceLine> INVOICE_LINE_ORDER =
            Comparator.comparingInt(EvidenceBundlePayload.EvidenceLine::lineNumber);
    private static final Comparator<PurchaseOrderLineFacts> PO_LINE_ORDER =
            Comparator.comparing(PurchaseOrderLineFacts::purchaseOrderLineId);
    private static final Comparator<ReceiptLineCandidate> RECEIPT_LINE_ORDER = Comparator
            .comparing(ReceiptLineCandidate::receiptDate)
            .thenComparing(ReceiptLineCandidate::receiptLineId)
            .thenComparing(ReceiptLineCandidate::receiptId);
    private static final Comparator<ReceiptFacts> RECEIPT_ORDER = Comparator.comparing(ReceiptFacts::receiptId);
    private static final Comparator<ReceiptLineFacts> RECEIPT_FACT_LINE_ORDER =
            Comparator.comparing(ReceiptLineFacts::receiptLineId);
    private static final Comparator<MatchException> EXCEPTION_ORDER = Comparator
            .comparingInt((MatchException e) -> e.lineNumber() == null ? Integer.MAX_VALUE : e.lineNumber())
            .thenComparing(e -> e.type().name())
            .thenComparing(e -> detailsSortKey(e.details()));

    private final ObjectMapper mapper = new ObjectMapper();

    public MatchComputation compute(MatchInput input) {
        List<EvidenceBundlePayload.EvidenceLine> invoiceLines = input.invoiceLines().stream()
                .sorted(INVOICE_LINE_ORDER)
                .toList();

        Map<Integer, String> mappingsByLine = effectiveMappingsByLine(input);

        Map<String, List<PurchaseOrderLineFacts>> poLinesByItem = input.purchasing().purchaseOrder().lines().stream()
                .sorted(PO_LINE_ORDER)
                .collect(Collectors.groupingBy(
                        PurchaseOrderLineFacts::itemId, LinkedHashMap::new, Collectors.toList()));

        Map<String, List<ReceiptLineCandidate>> receiptCandidatesByPoLine = receiptCandidates(input);
        Map<ReceiptKey, Long> remainingByReceiptLine = initialConfirmedBalances(receiptCandidatesByPoLine);

        List<MatchLineOutcome> outcomes = new ArrayList<>();
        List<MatchException> exceptions = new ArrayList<>();
        for (EvidenceBundlePayload.EvidenceLine invoiceLine : invoiceLines) {
            MatchLineOutcome outcome = matchLine(
                    invoiceLine, mappingsByLine, poLinesByItem, receiptCandidatesByPoLine, remainingByReceiptLine);
            outcomes.add(outcome);
            exceptions.addAll(outcome.exceptions());
        }

        addDuplicateException(input, exceptions);

        List<MatchException> orderedExceptions =
                exceptions.stream().sorted(EXCEPTION_ORDER).toList();
        boolean normal = orderedExceptions.isEmpty()
                && outcomes.stream().allMatch(outcome ->
                        outcome.status() == MatchLineStatus.MATCHED && outcome.hasCompleteExpectedPlan());

        ObjectNode payload = buildPayload(input, invoiceLines, mappingsByLine, outcomes, orderedExceptions, normal);
        String json = write(payload);
        return new MatchComputation(json, sha256Hex(json));
    }

    /**
     * Canonical effective mapping index keyed by invoice line number. The
     * resolver already returns one entry per line; requiring unique line numbers
     * here keeps the canonical payload independent of resolver list ordering.
     */
    private static Map<Integer, String> effectiveMappingsByLine(MatchInput input) {
        Map<Integer, String> mappings = new TreeMap<>();
        for (AppliedMapping mapping : input.appliedMappings()) {
            String previous = mappings.put(mapping.lineNumber(), mapping.itemId());
            if (previous != null && !previous.equals(mapping.itemId())) {
                throw new IllegalStateException(
                        "Conflicting effective mappings for invoice line " + mapping.lineNumber());
            }
        }
        return mappings;
    }

    /**
     * Initializes the shared remaining-receipt ledger from the confirmed receipt
     * lines. Keyed by structured receipt identity (receipt id and receipt line
     * id), never by a delimiter-concatenated string.
     */
    private static Map<ReceiptKey, Long> initialConfirmedBalances(
            Map<String, List<ReceiptLineCandidate>> receiptCandidatesByPoLine) {
        Map<ReceiptKey, Long> remaining = new HashMap<>();
        for (List<ReceiptLineCandidate> candidates : receiptCandidatesByPoLine.values()) {
            for (ReceiptLineCandidate candidate : candidates) {
                remaining.merge(candidate.key(), (long) candidate.confirmedQuantity(), Long::sum);
            }
        }
        return remaining;
    }

    private MatchLineOutcome matchLine(
            EvidenceBundlePayload.EvidenceLine invoiceLine,
            Map<Integer, String> mappingsByLine,
            Map<String, List<PurchaseOrderLineFacts>> poLinesByItem,
            Map<String, List<ReceiptLineCandidate>> receiptCandidatesByPoLine,
            Map<ReceiptKey, Long> remainingByReceiptLine) {
        int lineNumber = invoiceLine.lineNumber();
        // An effective case-local mapping takes precedence over the frozen
        // confirmed item so a human decision is what the re-match reflects.
        String confirmedItemId = mappingsByLine.containsKey(lineNumber)
                ? mappingsByLine.get(lineNumber)
                : blankToNull(invoiceLine.confirmedItemId());

        if (confirmedItemId == null) {
            Map<String, Object> details = orderedDetails();
            details.put("rawItemName", invoiceLine.rawItemName());
            details.put("invoiceQuantity", invoiceLine.quantity());
            MatchException exception = MatchException.line(MatchExceptionType.ITEM_UNCONFIRMED, lineNumber, details);
            return new MatchLineOutcome(
                    lineNumber,
                    invoiceLine.rawItemName(),
                    null,
                    MatchLineStatus.ITEM_UNCONFIRMED,
                    List.of(),
                    null,
                    invoiceLine.quantity(),
                    invoiceLine.unitPrice(),
                    0L,
                    List.of(),
                    0L,
                    List.of(exception));
        }

        List<PurchaseOrderLineFacts> candidates = poLinesByItem.getOrDefault(confirmedItemId, List.of());
        List<String> candidateIds = candidates.stream()
                .map(PurchaseOrderLineFacts::purchaseOrderLineId)
                .toList();

        if (candidates.size() != 1) {
            Map<String, Object> details = orderedDetails();
            details.put("confirmedItemId", confirmedItemId);
            details.put("candidatePoLineIds", candidateIds);
            details.put("invoiceQuantity", invoiceLine.quantity());
            MatchException exception =
                    MatchException.line(MatchExceptionType.EVIDENCE_INSUFFICIENT, lineNumber, details);
            return new MatchLineOutcome(
                    lineNumber,
                    invoiceLine.rawItemName(),
                    confirmedItemId,
                    MatchLineStatus.EVIDENCE_INSUFFICIENT,
                    candidateIds,
                    null,
                    invoiceLine.quantity(),
                    invoiceLine.unitPrice(),
                    0L,
                    List.of(),
                    0L,
                    List.of(exception));
        }

        PurchaseOrderLineFacts poLine = candidates.get(0);
        List<ReceiptLineCandidate> receiptCandidates =
                receiptCandidatesByPoLine.getOrDefault(poLine.purchaseOrderLineId(), List.of());

        // Aggregate remaining for this purchase order line immediately before
        // this invoice line's plan. Consuming it here carries the deduction to
        // every later invoice line that shares the same purchase order line.
        long available = 0L;
        for (ReceiptLineCandidate candidate : receiptCandidates) {
            available += remainingByReceiptLine.getOrDefault(candidate.key(), 0L);
        }

        List<PlannedAllocation> plan = new ArrayList<>();
        long remainingToPlan = invoiceLine.quantity();
        for (ReceiptLineCandidate candidate : receiptCandidates) {
            if (remainingToPlan <= 0L) {
                break;
            }
            long remaining = remainingByReceiptLine.getOrDefault(candidate.key(), 0L);
            if (remaining <= 0L) {
                continue;
            }
            long take = Math.min(remainingToPlan, remaining);
            plan.add(new PlannedAllocation(
                    candidate.receiptId(),
                    candidate.receiptLineId(),
                    candidate.receiptDate(),
                    candidate.receiptLineVersion(),
                    candidate.confirmedQuantity(),
                    (int) take));
            remainingByReceiptLine.put(candidate.key(), remaining - take);
            remainingToPlan -= take;
        }
        long planned = invoiceLine.quantity() - remainingToPlan;

        List<MatchException> lineExceptions = new ArrayList<>();
        if (invoiceLine.quantity() > available) {
            Map<String, Object> details = orderedDetails();
            details.put("invoiceQuantity", invoiceLine.quantity());
            details.put("availableConfirmedQuantity", available);
            lineExceptions.add(MatchException.line(
                    MatchExceptionType.QUANTITY_EXCEEDS_RECEIPT_BALANCE, lineNumber, details));
        }
        if (invoiceLine.unitPrice() != poLine.unitPrice().amount()) {
            Map<String, Object> details = orderedDetails();
            details.put("invoiceUnitPrice", invoiceLine.unitPrice());
            details.put("purchaseOrderUnitPrice", poLine.unitPrice().amount());
            lineExceptions.add(MatchException.line(MatchExceptionType.UNIT_PRICE_MISMATCH, lineNumber, details));
        }

        MatchPoLine matchedPoLine = new MatchPoLine(
                poLine.purchaseOrderLineId(),
                poLine.itemId(),
                poLine.orderedQuantity().value(),
                poLine.unitPrice().amount());

        return new MatchLineOutcome(
                lineNumber,
                invoiceLine.rawItemName(),
                confirmedItemId,
                MatchLineStatus.MATCHED,
                List.of(poLine.purchaseOrderLineId()),
                matchedPoLine,
                invoiceLine.quantity(),
                invoiceLine.unitPrice(),
                available,
                plan,
                planned,
                lineExceptions);
    }

    private static Map<String, List<ReceiptLineCandidate>> receiptCandidates(MatchInput input) {
        Map<String, List<ReceiptLineCandidate>> byPoLine = new HashMap<>();
        for (ReceiptFacts receipt : input.purchasing().receipts()) {
            if (receipt.status() != ReceiptStatus.CONFIRMED) {
                continue;
            }
            for (ReceiptLineFacts line : receipt.lines()) {
                byPoLine.computeIfAbsent(line.purchaseOrderLineId(), key -> new ArrayList<>())
                        .add(new ReceiptLineCandidate(
                                receipt.receiptId(),
                                receipt.receiptDate(),
                                line.receiptLineId(),
                                line.version(),
                                line.confirmedQuantity().value()));
            }
        }
        byPoLine.values().forEach(candidates -> candidates.sort(RECEIPT_LINE_ORDER));
        return byPoLine;
    }

    private static void addDuplicateException(MatchInput input, List<MatchException> exceptions) {
        List<String> otherCaseIds = input.duplicateCaseIds().stream()
                .map(UUID::toString)
                .sorted()
                .toList();
        if (otherCaseIds.isEmpty()) {
            return;
        }
        Map<String, Object> details = orderedDetails();
        details.put("normalizedInvoiceNumber", input.normalizedInvoiceNumber());
        details.put("otherCaseIds", otherCaseIds);
        exceptions.add(MatchException.caseLevel(MatchExceptionType.DUPLICATE_INVOICE_SUSPECTED, details));
    }

    private ObjectNode buildPayload(
            MatchInput input,
            List<EvidenceBundlePayload.EvidenceLine> invoiceLines,
            Map<Integer, String> mappingsByLine,
            List<MatchLineOutcome> outcomes,
            List<MatchException> exceptions,
            boolean normal) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("caseId", input.caseId().toString());
        root.put("supplierId", input.supplierId());
        root.put("purchaseOrderId", input.purchaseOrderId());
        root.put("invoiceNumber", input.invoiceNumber());
        root.put("normalizedInvoiceNumber", input.normalizedInvoiceNumber());
        root.put("caseVersion", input.caseVersion());

        ObjectNode bundle = root.putObject("evidenceBundle");
        bundle.put("id", input.evidenceBundleId().toString());
        bundle.put("version", input.evidenceBundleVersion());
        bundle.put("payloadHash", input.evidenceBundleHash());

        // Effective case-local mappings actually applied to a line present in
        // this bundle, sorted by line number. Empty when none are effective.
        ArrayNode appliedMappings = root.putArray("appliedMappings");
        for (EvidenceBundlePayload.EvidenceLine line : invoiceLines) {
            String itemId = mappingsByLine.get(line.lineNumber());
            if (itemId != null) {
                ObjectNode mapping = appliedMappings.addObject();
                mapping.put("lineNumber", line.lineNumber());
                mapping.put("itemId", itemId);
            }
        }

        ObjectNode purchasing = root.putObject("purchasingSnapshot");
        purchasing.put("snapshotVersion", input.purchasing().snapshotVersion());
        purchasing.put("purchaseOrderVersion", input.purchasing().purchaseOrder().version());
        purchasing.put("payloadHash", input.purchasingSnapshotHash());
        ArrayNode receipts = purchasing.putArray("receipts");
        input.purchasing().receipts().stream()
                .filter(receipt -> receipt.status() == ReceiptStatus.CONFIRMED)
                .sorted(RECEIPT_ORDER)
                .forEach(receipt -> writeReceipt(receipts, receipt));

        ObjectNode allocationPlan = root.putObject("allocationPlan");
        allocationPlan.put("consuming", false);
        allocationPlan.put("mode", "NON_CONSUMING_EXPECTED_PLAN_V1");
        allocationPlan.put("fifoOrdering", "receiptDate,receiptLineId,receiptId");

        ArrayNode lineOutcomes = root.putArray("lineOutcomes");
        outcomes.forEach(outcome -> writeLineOutcome(lineOutcomes, outcome));

        ArrayNode exceptionsNode = root.putArray("exceptions");
        exceptions.forEach(exception -> writeException(exceptionsNode, exception));

        root.put("normal", normal);
        return root;
    }

    private void writeReceipt(ArrayNode receipts, ReceiptFacts receipt) {
        ObjectNode node = receipts.addObject();
        node.put("receiptId", receipt.receiptId());
        node.put("status", receipt.status().name());
        node.put("receiptDate", receipt.receiptDate().toString());
        node.put("version", receipt.version());
        ArrayNode lines = node.putArray("lines");
        receipt.lines().stream().sorted(RECEIPT_FACT_LINE_ORDER).forEach(line -> {
            ObjectNode lineNode = lines.addObject();
            lineNode.put("receiptLineId", line.receiptLineId());
            lineNode.put("version", line.version());
            lineNode.put("purchaseOrderLineId", line.purchaseOrderLineId());
            lineNode.put("confirmedQuantity", line.confirmedQuantity().value());
        });
    }

    private void writeLineOutcome(ArrayNode lineOutcomes, MatchLineOutcome outcome) {
        ObjectNode node = lineOutcomes.addObject();
        node.put("lineNumber", outcome.lineNumber());
        node.put("rawItemName", outcome.rawItemName());
        node.put("confirmedItemId", outcome.confirmedItemId());
        node.put("status", outcome.status().name());

        ArrayNode candidateIds = node.putArray("candidatePoLineIds");
        outcome.candidatePoLineIds().forEach(candidateIds::add);

        if (outcome.purchaseOrderLine() == null) {
            node.putNull("purchaseOrderLine");
        } else {
            MatchPoLine poLine = outcome.purchaseOrderLine();
            ObjectNode poLineNode = node.putObject("purchaseOrderLine");
            poLineNode.put("purchaseOrderLineId", poLine.purchaseOrderLineId());
            poLineNode.put("itemId", poLine.itemId());
            poLineNode.put("orderedQuantity", poLine.orderedQuantity());
            poLineNode.put("unitPrice", poLine.unitPrice());
        }

        node.put("invoiceQuantity", outcome.invoiceQuantity());
        node.put("invoiceUnitPrice", outcome.invoiceUnitPrice());
        node.put("availableConfirmedQuantity", outcome.availableConfirmedQuantity());
        node.put("plannedQuantity", outcome.plannedQuantity());

        ArrayNode plan = node.putArray("expectedAllocationPlan");
        outcome.expectedAllocationPlan().forEach(allocation -> {
            ObjectNode allocationNode = plan.addObject();
            allocationNode.put("receiptId", allocation.receiptId());
            allocationNode.put("receiptLineId", allocation.receiptLineId());
            allocationNode.put("receiptDate", allocation.receiptDate().toString());
            allocationNode.put("receiptLineVersion", allocation.receiptLineVersion());
            allocationNode.put("confirmedQuantity", allocation.confirmedQuantity());
            allocationNode.put("plannedQuantity", allocation.plannedQuantity());
        });

        ArrayNode lineExceptions = node.putArray("exceptions");
        outcome.exceptions().forEach(exception -> writeException(lineExceptions, exception));
    }

    private void writeException(ArrayNode target, MatchException exception) {
        ObjectNode node = target.addObject();
        node.put("type", exception.type().name());
        if (exception.lineNumber() == null) {
            node.putNull("lineNumber");
        } else {
            node.put("lineNumber", exception.lineNumber());
        }
        node.set("details", mapper.valueToTree(exception.details()));
    }

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical match payload serialization failed", e);
        }
    }

    private static Map<String, Object> orderedDetails() {
        return new LinkedHashMap<>();
    }

    private static String detailsSortKey(Map<String, Object> details) {
        StringBuilder key = new StringBuilder();
        details.forEach((name, value) -> key.append(name).append('=').append(String.valueOf(value)).append(';'));
        return key.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class ReceiptLineCandidate {
        private final String receiptId;
        private final LocalDate receiptDate;
        private final String receiptLineId;
        private final long receiptLineVersion;
        private final int confirmedQuantity;

        private ReceiptLineCandidate(
                String receiptId,
                LocalDate receiptDate,
                String receiptLineId,
                long receiptLineVersion,
                int confirmedQuantity) {
            this.receiptId = receiptId;
            this.receiptDate = receiptDate;
            this.receiptLineId = receiptLineId;
            this.receiptLineVersion = receiptLineVersion;
            this.confirmedQuantity = confirmedQuantity;
        }

        private String receiptId() {
            return receiptId;
        }

        private LocalDate receiptDate() {
            return receiptDate;
        }

        private String receiptLineId() {
            return receiptLineId;
        }

        private long receiptLineVersion() {
            return receiptLineVersion;
        }

        private int confirmedQuantity() {
            return confirmedQuantity;
        }

        private ReceiptKey key() {
            return new ReceiptKey(receiptId, receiptLineId);
        }
    }

    /**
     * Structured identity of one receipt line in the shared remaining balance
     * ledger. Using a record rather than a delimiter-joined string means receipt
     * and receipt-line ids containing the delimiter can never collide.
     */
    private record ReceiptKey(String receiptId, String receiptLineId) {
    }
}
