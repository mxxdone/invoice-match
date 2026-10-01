package com.invoicematch.core.matching.application;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    public static final String SCHEMA_VERSION = MatchResultPayloadEncoder.SCHEMA_VERSION;

    private static final Comparator<EvidenceBundlePayload.EvidenceLine> INVOICE_LINE_ORDER =
            Comparator.comparingInt(EvidenceBundlePayload.EvidenceLine::lineNumber);
    private static final Comparator<PurchaseOrderLineFacts> PO_LINE_ORDER =
            Comparator.comparing(PurchaseOrderLineFacts::purchaseOrderLineId);
    private static final Comparator<ReceiptLineCandidate> RECEIPT_LINE_ORDER = Comparator
            .comparing(ReceiptLineCandidate::receiptDate)
            .thenComparing(ReceiptLineCandidate::receiptLineId)
            .thenComparing(ReceiptLineCandidate::receiptId);
    private static final Comparator<MatchException> EXCEPTION_ORDER = Comparator
            .comparingInt((MatchException e) -> e.lineNumber() == null ? Integer.MAX_VALUE : e.lineNumber())
            .thenComparing(e -> e.type().name())
            .thenComparing(e -> detailsSortKey(e.details()));

    public MatchComputation compute(MatchInput input) {
        List<EvidenceBundlePayload.EvidenceLine> invoiceLines = input.invoiceLines().stream()
                .sorted(INVOICE_LINE_ORDER)
                .toList();

        Map<Integer, AppliedMapping> mappingsByLine = effectiveMappingsByLine(input);

        Map<String, List<PurchaseOrderLineFacts>> poLinesByItem = input.purchasing().purchaseOrder().lines().stream()
                .sorted(PO_LINE_ORDER)
                .collect(Collectors.groupingBy(
                        PurchaseOrderLineFacts::itemId, LinkedHashMap::new, Collectors.toList()));
        Map<String, PurchaseOrderLineFacts> poLinesById = input.purchasing().purchaseOrder().lines().stream()
                .collect(Collectors.toMap(
                        PurchaseOrderLineFacts::purchaseOrderLineId, line -> line, (first, second) -> first));

        Map<String, List<ReceiptLineCandidate>> receiptCandidatesByPoLine = receiptCandidates(input);
        Map<ReceiptKey, Long> remainingByReceiptLine = initialConfirmedBalances(receiptCandidatesByPoLine);

        List<MatchLineOutcome> outcomes = new ArrayList<>();
        List<MatchException> exceptions = new ArrayList<>();
        for (EvidenceBundlePayload.EvidenceLine invoiceLine : invoiceLines) {
            MatchLineOutcome outcome = matchLine(
                    invoiceLine,
                    mappingsByLine,
                    poLinesByItem,
                    poLinesById,
                    receiptCandidatesByPoLine,
                    remainingByReceiptLine);
            outcomes.add(outcome);
            exceptions.addAll(outcome.exceptions());
        }

        addDuplicateException(input, exceptions);

        List<MatchException> orderedExceptions =
                exceptions.stream().sorted(EXCEPTION_ORDER).toList();
        boolean normal = orderedExceptions.isEmpty()
                && outcomes.stream().allMatch(outcome ->
                        outcome.status() == MatchLineStatus.MATCHED && outcome.hasCompleteExpectedPlan());

        MatchResultPayloadEncoder.Encoded encoded =
                MatchResultPayloadEncoder.encode(input, invoiceLines, mappingsByLine, outcomes, orderedExceptions, normal);
        return new MatchComputation(encoded.json(), encoded.hash(), normal, List.copyOf(outcomes));
    }

    /**
     * Canonical effective mapping index keyed by invoice line number. The
     * resolver already returns one entry per line; requiring unique line numbers
     * here keeps the canonical payload independent of resolver list ordering.
     */
    private static Map<Integer, AppliedMapping> effectiveMappingsByLine(MatchInput input) {
        Map<Integer, AppliedMapping> mappings = new TreeMap<>();
        for (AppliedMapping mapping : input.appliedMappings()) {
            AppliedMapping previous = mappings.put(mapping.lineNumber(), mapping);
            if (previous != null && !previous.equals(mapping)) {
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
            Map<Integer, AppliedMapping> mappingsByLine,
            Map<String, List<PurchaseOrderLineFacts>> poLinesByItem,
            Map<String, PurchaseOrderLineFacts> poLinesById,
            Map<String, List<ReceiptLineCandidate>> receiptCandidatesByPoLine,
            Map<ReceiptKey, Long> remainingByReceiptLine) {
        int lineNumber = invoiceLine.lineNumber();
        AppliedMapping mapping = mappingsByLine.get(lineNumber);

        // An effective case-local mapping takes precedence over the frozen
        // confirmed item and pins the exact purchase order line the human chose.
        // The stored (itemId, purchaseOrderLineId) pair must still match the
        // current active line exactly: re-resolving by item could silently
        // retarget the mapping, and applying it to a line whose item changed
        // would match the frozen item against a different purchase order item.
        if (mapping != null) {
            PurchaseOrderLineFacts mappedLine = poLinesById.get(mapping.purchaseOrderLineId());
            boolean itemMatches = mappedLine != null && mappedLine.itemId().equals(mapping.itemId());
            if (!itemMatches) {
                return mappingEvidenceInsufficient(invoiceLine, mapping, mappedLine, poLinesByItem);
            }
            return planLine(
                    invoiceLine,
                    mappedLine,
                    mapping.itemId(),
                    List.of(mappedLine.purchaseOrderLineId()),
                    receiptCandidatesByPoLine,
                    remainingByReceiptLine);
        }

        String confirmedItemId = blankToNull(invoiceLine.confirmedItemId());
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

        return planLine(
                invoiceLine,
                candidates.get(0),
                confirmedItemId,
                candidateIds,
                receiptCandidatesByPoLine,
                remainingByReceiptLine);
    }

    /**
     * Reports that an effective mapping can no longer be applied because its
     * chosen purchase order line is missing, or is still present but now carries
     * a different item. Diagnostics name the chosen line, the item it now holds
     * (if any) and the currently active lines for the mapped item, so a person
     * can decide whether to re-map. The line is never matched against the stale
     * item or silently retargeted.
     */
    private MatchLineOutcome mappingEvidenceInsufficient(
            EvidenceBundlePayload.EvidenceLine invoiceLine,
            AppliedMapping mapping,
            PurchaseOrderLineFacts currentLine,
            Map<String, List<PurchaseOrderLineFacts>> poLinesByItem) {
        int lineNumber = invoiceLine.lineNumber();
        List<String> activeItemCandidates = poLinesByItem
                .getOrDefault(mapping.itemId(), List.of()).stream()
                .map(PurchaseOrderLineFacts::purchaseOrderLineId)
                .toList();
        Map<String, Object> details = orderedDetails();
        details.put("confirmedItemId", mapping.itemId());
        details.put("mappedPurchaseOrderLineId", mapping.purchaseOrderLineId());
        if (currentLine != null) {
            details.put("currentPurchaseOrderLineItemId", currentLine.itemId());
        }
        details.put("candidatePoLineIds", activeItemCandidates);
        details.put("invoiceQuantity", invoiceLine.quantity());
        MatchException exception =
                MatchException.line(MatchExceptionType.EVIDENCE_INSUFFICIENT, lineNumber, details);
        return new MatchLineOutcome(
                lineNumber,
                invoiceLine.rawItemName(),
                mapping.itemId(),
                MatchLineStatus.EVIDENCE_INSUFFICIENT,
                activeItemCandidates,
                null,
                invoiceLine.quantity(),
                invoiceLine.unitPrice(),
                0L,
                List.of(),
                0L,
                List.of(exception));
    }

    /**
     * Plans one invoice line against one exact purchase order line, sharing the
     * zero-tolerance price and non-consuming FIFO balance logic between the
     * item-resolved and mapping-resolved paths.
     */
    private MatchLineOutcome planLine(
            EvidenceBundlePayload.EvidenceLine invoiceLine,
            PurchaseOrderLineFacts poLine,
            String confirmedItemId,
            List<String> candidateIds,
            Map<String, List<ReceiptLineCandidate>> receiptCandidatesByPoLine,
            Map<ReceiptKey, Long> remainingByReceiptLine) {
        int lineNumber = invoiceLine.lineNumber();
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
                candidateIds,
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

    private static Map<String, Object> orderedDetails() {
        return new LinkedHashMap<>();
    }

    private static String detailsSortKey(Map<String, Object> details) {
        StringBuilder key = new StringBuilder();
        details.forEach((name, value) -> key.append(name).append('=').append(String.valueOf(value)).append(';'));
        return key.toString();
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
