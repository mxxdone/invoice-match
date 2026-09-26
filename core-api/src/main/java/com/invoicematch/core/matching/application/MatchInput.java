package com.invoicematch.core.matching.application;

import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Every semantic input of one deterministic match, already loaded. The pure
 * engine consumes this and never reads a repository, so repository or list
 * ordering can never change the result.
 */
public record MatchInput(
        UUID caseId,
        String supplierId,
        String purchaseOrderId,
        String invoiceNumber,
        String normalizedInvoiceNumber,
        long caseVersion,
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        String evidenceBundleHash,
        List<EvidenceBundlePayload.EvidenceLine> invoiceLines,
        PurchaseOrderAggregate purchasing,
        String purchasingSnapshotHash,
        List<UUID> duplicateCaseIds,
        List<AppliedMapping> appliedMappings) {

    public MatchInput {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(purchasing, "purchasing");
        Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        invoiceLines = List.copyOf(Objects.requireNonNull(invoiceLines, "invoiceLines"));
        duplicateCaseIds = List.copyOf(Objects.requireNonNull(duplicateCaseIds, "duplicateCaseIds"));
        appliedMappings = List.copyOf(Objects.requireNonNull(appliedMappings, "appliedMappings"));
    }
}
