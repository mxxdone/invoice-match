package com.invoicematch.core.purchasingreference.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A receipt fact from the external purchasing system with its own external
 * version and receipt lines.
 */
public record ReceiptFacts(
        String receiptId,
        ReceiptStatus status,
        LocalDate receiptDate,
        long version,
        List<ReceiptLineFacts> lines) {

    public ReceiptFacts {
        receiptId = PurchaseOrderLineFacts.requireText(receiptId, "receiptId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(receiptDate, "receiptDate");
        if (version < 0) {
            throw new DomainValidationException("receipt version must not be negative: " + version);
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }
}
