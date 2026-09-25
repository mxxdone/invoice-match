package com.invoicematch.core.purchasingreference.persistence;

import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import com.invoicematch.core.shared.domain.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A receipt within the current local snapshot, with its own external version
 * and reference to its parent purchase order snapshot.
 */
@Entity
@Table(name = "receipt_snapshot")
public class ReceiptSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderId;

    @Column(name = "receipt_id", nullable = false, updatable = false, length = 64)
    private String receiptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReceiptStatus status;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    @Column(name = "external_version", nullable = false)
    private long externalVersion;

    protected ReceiptSnapshot() {
    }

    private ReceiptSnapshot(
            UUID id,
            String purchaseOrderId,
            String receiptId,
            ReceiptStatus status,
            LocalDate receiptDate,
            long externalVersion) {
        if (externalVersion < 0) {
            throw new DomainValidationException("externalVersion must not be negative: " + externalVersion);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.purchaseOrderId = requireText(purchaseOrderId, "purchaseOrderId");
        this.receiptId = requireText(receiptId, "receiptId");
        this.status = Objects.requireNonNull(status, "status");
        this.receiptDate = Objects.requireNonNull(receiptDate, "receiptDate");
        this.externalVersion = externalVersion;
    }

    public static ReceiptSnapshot create(UUID id, String purchaseOrderId, ReceiptFacts facts) {
        return new ReceiptSnapshot(
                id, purchaseOrderId, facts.receiptId(), facts.status(), facts.receiptDate(), facts.version());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public String purchaseOrderId() {
        return purchaseOrderId;
    }

    public String receiptId() {
        return receiptId;
    }

    public ReceiptStatus status() {
        return status;
    }

    public LocalDate receiptDate() {
        return receiptDate;
    }

    public long externalVersion() {
        return externalVersion;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReceiptSnapshot receipt && Objects.equals(id, receipt.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
