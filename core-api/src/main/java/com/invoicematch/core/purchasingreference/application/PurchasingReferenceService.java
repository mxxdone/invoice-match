package com.invoicematch.core.purchasingreference.application;

import com.invoicematch.core.purchasingreference.domain.ExternalFactUnconfirmedException;
import com.invoicematch.core.purchasingreference.domain.ExternalReferenceMismatchException;
import com.invoicematch.core.purchasingreference.domain.InvalidExternalFactException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderStatus;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import com.invoicematch.core.purchasingreference.domain.RefreshResult;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read-only refresh of the local purchase order reference snapshot from the
 * external purchasing system. The external fetch happens outside any database
 * transaction; the fetched aggregate is validated and then applied atomically
 * by {@link PurchaseOrderSnapshotStore}.
 */
@Service
public class PurchasingReferenceService {

    private final PurchasingSystemClient client;
    private final SnapshotPayloadHasher hasher;
    private final PurchaseOrderSnapshotStore store;
    private final Clock clock;

    public PurchasingReferenceService(
            PurchasingSystemClient client,
            SnapshotPayloadHasher hasher,
            PurchaseOrderSnapshotStore store,
            Clock clock) {
        this.client = client;
        this.hasher = hasher;
        this.store = store;
        this.clock = clock;
    }

    /**
     * Fetches, validates and applies one external aggregate. The fetch and
     * validation happen outside the store's transaction; the caller's public
     * behaviour is unchanged.
     */
    public RefreshResult refresh(RefreshPurchaseOrderCommand command) {
        return applyPrepared(fetchValidated(command));
    }

    /**
     * Fetches, validates and canonicalizes one external aggregate without
     * touching the database. Intended to be called outside a transaction so the
     * external HTTP call never runs while a transaction or row lock is held.
     */
    public PreparedPurchaseOrderSnapshot fetchValidated(RefreshPurchaseOrderCommand command) {
        PurchaseOrderAggregate aggregate = client.fetch(command.purchaseOrderId());
        validate(command, aggregate);
        SnapshotPayloadHasher.CanonicalPayload canonical = hasher.canonicalize(aggregate);
        return new PreparedPurchaseOrderSnapshot(aggregate, canonical.json(), canonical.hash(), clock.instant());
    }

    /**
     * Applies a previously fetched, validated aggregate. The store joins the
     * caller's transaction when one is active, so the snapshot write can be
     * committed atomically with the caller's business effect.
     */
    public RefreshResult applyPrepared(PreparedPurchaseOrderSnapshot prepared) {
        return store.apply(
                prepared.aggregate(), prepared.canonicalJson(), prepared.canonicalHash(), prepared.retrievedAt());
    }

    private void validate(RefreshPurchaseOrderCommand command, PurchaseOrderAggregate aggregate) {
        if (!aggregate.purchaseOrderId().equals(command.purchaseOrderId())) {
            throw new ExternalReferenceMismatchException("Requested purchase order " + command.purchaseOrderId()
                    + " but external aggregate described " + aggregate.purchaseOrderId());
        }
        if (!aggregate.purchaseOrder().supplierId().equals(command.expectedSupplierId())) {
            throw new ExternalReferenceMismatchException("Purchase order " + command.purchaseOrderId()
                    + " belongs to supplier " + aggregate.purchaseOrder().supplierId()
                    + " but expected " + command.expectedSupplierId());
        }
        if (aggregate.purchaseOrder().status() != PurchaseOrderStatus.CONFIRMED) {
            throw new ExternalFactUnconfirmedException("Purchase order " + command.purchaseOrderId()
                    + " is not confirmed: " + aggregate.purchaseOrder().status());
        }

        Set<String> lineIds = new HashSet<>();
        for (var line : aggregate.purchaseOrder().lines()) {
            if (!lineIds.add(line.purchaseOrderLineId())) {
                throw new InvalidExternalFactException(
                        "Duplicate purchase order line id " + line.purchaseOrderLineId());
            }
        }

        Set<String> receiptIds = new HashSet<>();
        for (ReceiptFacts receipt : aggregate.receipts()) {
            if (!receiptIds.add(receipt.receiptId())) {
                throw new InvalidExternalFactException("Duplicate receipt id " + receipt.receiptId());
            }
            if (receipt.status() != ReceiptStatus.CONFIRMED) {
                throw new ExternalFactUnconfirmedException(
                        "Receipt " + receipt.receiptId() + " is not confirmed: " + receipt.status());
            }
            Set<String> receiptLineIds = new HashSet<>();
            for (ReceiptLineFacts line : receipt.lines()) {
                if (!receiptLineIds.add(line.receiptLineId())) {
                    throw new InvalidExternalFactException("Duplicate receipt line id " + line.receiptLineId());
                }
                if (!lineIds.contains(line.purchaseOrderLineId())) {
                    throw new ExternalReferenceMismatchException("Receipt line " + line.receiptLineId()
                            + " references unknown purchase order line " + line.purchaseOrderLineId());
                }
            }
        }
    }
}
