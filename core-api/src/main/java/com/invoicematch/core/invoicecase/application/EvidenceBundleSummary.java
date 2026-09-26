package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import java.time.Instant;

/**
 * Frozen evidence bundle metadata without the payload.
 */
public record EvidenceBundleSummary(int version, String payloadHash, Instant submittedAt) {

    public static EvidenceBundleSummary from(EvidenceBundle bundle) {
        return new EvidenceBundleSummary(bundle.versionNumber(), bundle.payloadHash(), bundle.submittedAt());
    }
}
