package com.invoicematch.core.purchasingreference.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.SupplierId;
import java.util.List;
import java.util.Objects;

/**
 * Purchase order header and line facts from the external purchasing system.
 */
public record PurchaseOrderFacts(
        long version,
        PurchaseOrderStatus status,
        SupplierId supplierId,
        String supplierName,
        List<PurchaseOrderLineFacts> lines) {

    public PurchaseOrderFacts {
        if (version < 0) {
            throw new DomainValidationException("purchase order version must not be negative: " + version);
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(supplierId, "supplierId");
        supplierName = PurchaseOrderLineFacts.requireText(supplierName, "supplierName");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }
}
