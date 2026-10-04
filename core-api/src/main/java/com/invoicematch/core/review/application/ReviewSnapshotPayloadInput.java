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
        String purchasingSnapshotHash,
        ProposalEvidenceReader.Reference proposal) {

    public ReviewSnapshotPayloadInput(UUID caseId,long caseVersion,UUID bundleId,int bundleVersion,String bundleHash,
            UUID matchId,int matchNumber,String matchHash,int watermark,String matchPayload,List<AppliedMapping> mappings,
            List<EvidenceBundlePayload.EvidenceLine> lines,long purchasingVersion,long orderVersion,String purchasingHash) {
        this(caseId,caseVersion,bundleId,bundleVersion,bundleHash,matchId,matchNumber,matchHash,watermark,matchPayload,mappings,lines,purchasingVersion,orderVersion,purchasingHash,null);
    }
    public ReviewSnapshotPayloadInput withProposal(ProposalEvidenceReader.Reference proof) {
        return new ReviewSnapshotPayloadInput(caseId,caseVersion,evidenceBundleId,evidenceBundleVersion,evidenceBundleHash,matchResultId,
            matchResultNumber,matchResultHash,mappingWatermark,matchResultPayload,appliedMappings,invoiceLines,purchasingSnapshotVersion,purchaseOrderVersion,purchasingSnapshotHash,proof);
    }
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
