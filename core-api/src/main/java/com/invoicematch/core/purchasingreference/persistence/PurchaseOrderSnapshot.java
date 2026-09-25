package com.invoicematch.core.purchasingreference.persistence;

import com.invoicematch.core.purchasingreference.domain.PurchaseOrderStatus;
import com.invoicematch.core.shared.domain.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Current local snapshot of one external purchase order. It is mutable by
 * design: a newer external aggregate snapshot version replaces it atomically.
 * The row keeps the external version and canonical payload hash that decide
 * whether an incoming refresh is newer, stale, unchanged or in conflict.
 */
@Entity
@Table(name = "purchase_order_snapshot")
public class PurchaseOrderSnapshot {

    @Id
    @Column(name = "purchase_order_id", nullable = false, updatable = false, length = 64)
    private String purchaseOrderId;

    @Column(name = "supplier_id", nullable = false, length = 64)
    private String supplierId;

    @Column(name = "supplier_name", nullable = false, length = 200)
    private String supplierName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseOrderStatus status;

    @Column(name = "external_version", nullable = false)
    private long externalVersion;

    @Column(name = "payload_hash", nullable = false, length = 128)
    private String payloadHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PurchaseOrderSnapshot() {
    }

    private PurchaseOrderSnapshot(
            String purchaseOrderId,
            String supplierId,
            String supplierName,
            PurchaseOrderStatus status,
            long externalVersion,
            String payloadHash,
            String payload,
            Instant retrievedAt) {
        if (externalVersion < 0) {
            throw new DomainValidationException("externalVersion must not be negative: " + externalVersion);
        }
        this.purchaseOrderId = requireText(purchaseOrderId, "purchaseOrderId");
        this.supplierId = requireText(supplierId, "supplierId");
        this.supplierName = requireText(supplierName, "supplierName");
        this.status = Objects.requireNonNull(status, "status");
        this.externalVersion = externalVersion;
        this.payloadHash = requireText(payloadHash, "payloadHash");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
        this.updatedAt = retrievedAt;
    }

    public static PurchaseOrderSnapshot create(
            String purchaseOrderId,
            String supplierId,
            String supplierName,
            PurchaseOrderStatus status,
            long externalVersion,
            String payloadHash,
            String payload,
            Instant retrievedAt) {
        return new PurchaseOrderSnapshot(
                purchaseOrderId, supplierId, supplierName, status, externalVersion, payloadHash, payload, retrievedAt);
    }

    /**
     * Replaces this snapshot with a newer external aggregate version. Children
     * are replaced separately in the same transaction.
     */
    public void replaceWith(
            String supplierId,
            String supplierName,
            PurchaseOrderStatus status,
            long externalVersion,
            String payloadHash,
            String payload,
            Instant retrievedAt) {
        if (externalVersion < externalVersion()) {
            throw new DomainValidationException(
                    "Cannot replace snapshot with older version " + externalVersion + " < " + externalVersion());
        }
        this.supplierId = requireText(supplierId, "supplierId");
        this.supplierName = requireText(supplierName, "supplierName");
        this.status = Objects.requireNonNull(status, "status");
        this.externalVersion = externalVersion;
        this.payloadHash = requireText(payloadHash, "payloadHash");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
        this.updatedAt = retrievedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        return value;
    }

    public String purchaseOrderId() {
        return purchaseOrderId;
    }

    public String supplierId() {
        return supplierId;
    }

    public String supplierName() {
        return supplierName;
    }

    public PurchaseOrderStatus status() {
        return status;
    }

    public long externalVersion() {
        return externalVersion;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public String payload() {
        return payload;
    }

    public Instant retrievedAt() {
        return retrievedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PurchaseOrderSnapshot snapshot
                && Objects.equals(purchaseOrderId, snapshot.purchaseOrderId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(purchaseOrderId);
    }
}
