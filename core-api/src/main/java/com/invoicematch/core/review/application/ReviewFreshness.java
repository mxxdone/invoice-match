package com.invoicematch.core.review.application;

import com.invoicematch.core.review.domain.StaleReason;
import java.util.List;
import java.util.UUID;

/**
 * Machine-checkable freshness of one review snapshot against the current case,
 * evidence bundle, match result, effective mappings and purchasing snapshot.
 * {@code current} is true only when {@code reasons} is empty, and P1-07
 * approval can reuse this contract before allocating.
 */
public record ReviewFreshness(
        UUID invoiceCaseId,
        UUID reviewSnapshotId,
        int snapshotNumber,
        boolean current,
        List<StaleReason> reasons,
        long snapshotCaseVersion,
        long currentCaseVersion,
        String currentCaseStatus,
        UUID snapshotEvidenceBundleId,
        UUID latestEvidenceBundleId,
        Integer latestEvidenceBundleVersion,
        UUID snapshotMatchResultId,
        UUID latestMatchResultId,
        Integer latestMatchResultNumber,
        int snapshotMappingWatermark,
        int currentMappingWatermark,
        long snapshotPurchasingSnapshotVersion,
        Long currentPurchasingSnapshotVersion,
        String snapshotPurchasingSnapshotHash,
        String currentPurchasingSnapshotHash) {

    public ReviewFreshness {
        reasons = List.copyOf(reasons);
    }
}
