package com.invoicematch.core.invoicecase.domain;

import java.util.UUID;

/**
 * No frozen evidence bundle exists for the requested case and version.
 */
public class EvidenceBundleNotFoundException extends RuntimeException {

    public EvidenceBundleNotFoundException(UUID caseId, int versionNumber) {
        super("Evidence bundle version " + versionNumber + " not found for invoice case " + caseId);
    }
}
