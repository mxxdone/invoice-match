package com.invoicematch.core.review.application;

import com.invoicematch.core.invoicecase.application.EvidenceBundlePayload;
import com.invoicematch.core.matching.application.AppliedMapping;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Every semantic input of one frozen review snapshot, already loaded. The pure
 * payload builder consumes this and never reads a repository.
 */
public record ReviewSnapshotPayloadInput(
        UUID caseId,
        long caseVersion,
        UUID evidenceBundleId,
        int evidenceBundleVersion,
        String evidenceBundleHash,
        UUID matchResultId,
        int matchResultNumber,
        String matchResultHash,
        int mappingWatermark,
        String matchResultPayload,
        List<AppliedMapping> appliedMappings,
        List<EvidenceBundlePayload.EvidenceLine> invoiceLines,
        long purchasingSnapshotVersion,
        long purchaseOrderVersion,
        String purchasingSnapshotHash) {

    public static final Comparator<AppliedMapping> MAPPING_ORDER =
            Comparator.comparingInt(AppliedMapping::lineNumber);
    public static final Comparator<EvidenceBundlePayload.EvidenceLine> LINE_ORDER =
            Comparator.comparingInt(EvidenceBundlePayload.EvidenceLine::lineNumber);

    public ReviewSnapshotPayloadInput {
        appliedMappings = List.copyOf(appliedMappings).stream()
                .sorted(MAPPING_ORDER)
                .toList();
        invoiceLines = List.copyOf(invoiceLines).stream()
                .sorted(LINE_ORDER)
                .toList();
    }
}
