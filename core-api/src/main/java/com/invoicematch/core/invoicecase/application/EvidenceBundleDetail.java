package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import java.time.Instant;

/**
 * One frozen evidence bundle version including the canonical payload that the
 * hash binds. Sealed versions are read-only.
 */
public record EvidenceBundleDetail(int version, String payloadHash, String payload, Instant submittedAt) {

    public static EvidenceBundleDetail from(EvidenceBundle bundle) {
        return new EvidenceBundleDetail(
                bundle.versionNumber(), bundle.payloadHash(), bundle.payload(), bundle.submittedAt());
    }
}
