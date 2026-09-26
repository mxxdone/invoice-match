package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import java.time.Instant;
import java.util.Objects;

/**
 * An external purchase order aggregate that has already been fetched,
 * validated and canonicalized, but not yet written to the local snapshot.
 *
 * <p>It is the seam that keeps the external HTTP call outside any database
 * transaction while still letting the caller apply the result inside its own
 * transaction. {@link PurchasingReferenceService#fetchValidated} produces it and
 * {@link PurchasingReferenceService#applyPrepared} consumes it.
 */
public record PreparedPurchaseOrderSnapshot(
        PurchaseOrderAggregate aggregate, String canonicalJson, String canonicalHash, Instant retrievedAt) {

    public PreparedPurchaseOrderSnapshot {
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        Objects.requireNonNull(canonicalHash, "canonicalHash");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
    }
}
