package com.invoicematch.core.matching.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Deterministic output of the 3-way match for one evidence version: the
 * exception types, calculated values and supporting facts. A re-match creates a
 * new result instead of editing an existing one.
 */
@Entity
@Table(name = "match_result")
@Immutable
public class MatchResult {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "evidence_bundle_id", nullable = false, updatable = false)
    private UUID evidenceBundleId;

    @Column(name = "result_number", nullable = false, updatable = false)
    private int resultNumber;

    @Column(name = "result_hash", nullable = false, updatable = false, length = 128)
    private String resultHash;

    @Column(name = "purchasing_snapshot_version", nullable = false, updatable = false)
    private long purchasingSnapshotVersion;

    @Column(name = "purchasing_snapshot_hash", nullable = false, updatable = false, length = 128)
    private String purchasingSnapshotHash;

    @Column(name = "mapping_watermark", nullable = false, updatable = false)
    private int mappingWatermark;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MatchResult() {
    }

    private MatchResult(
            UUID id,
            UUID invoiceCaseId,
            UUID evidenceBundleId,
            int resultNumber,
            String resultHash,
            long purchasingSnapshotVersion,
            String purchasingSnapshotHash,
            int mappingWatermark,
            String payload,
            Instant createdAt) {
        if (resultNumber <= 0) {
            throw new DomainValidationException("resultNumber must be positive: " + resultNumber);
        }
        if (resultHash == null || resultHash.isBlank()) {
            throw new DomainValidationException("resultHash must not be blank");
        }
        if (purchasingSnapshotVersion < 0) {
            throw new DomainValidationException(
                    "purchasingSnapshotVersion must not be negative: " + purchasingSnapshotVersion);
        }
        if (purchasingSnapshotHash == null || purchasingSnapshotHash.isBlank()) {
            throw new DomainValidationException("purchasingSnapshotHash must not be blank");
        }
        if (mappingWatermark < 0) {
            throw new DomainValidationException("mappingWatermark must not be negative: " + mappingWatermark);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.evidenceBundleId = Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        this.resultNumber = resultNumber;
        this.resultHash = resultHash;
        this.purchasingSnapshotVersion = purchasingSnapshotVersion;
        this.purchasingSnapshotHash = purchasingSnapshotHash;
        this.mappingWatermark = mappingWatermark;
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static MatchResult record(
            UUID id,
            UUID invoiceCaseId,
            UUID evidenceBundleId,
            int resultNumber,
            String resultHash,
            long purchasingSnapshotVersion,
            String purchasingSnapshotHash,
            int mappingWatermark,
            String payload,
            Instant createdAt) {
        return new MatchResult(
                id,
                invoiceCaseId,
                evidenceBundleId,
                resultNumber,
                resultHash,
                purchasingSnapshotVersion,
                purchasingSnapshotHash,
                mappingWatermark,
                payload,
                createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID invoiceCaseId() {
        return invoiceCaseId;
    }

    public UUID evidenceBundleId() {
        return evidenceBundleId;
    }

    public int resultNumber() {
        return resultNumber;
    }

    public String resultHash() {
        return resultHash;
    }

    public long purchasingSnapshotVersion() {
        return purchasingSnapshotVersion;
    }

    public String purchasingSnapshotHash() {
        return purchasingSnapshotHash;
    }

    public int mappingWatermark() {
        return mappingWatermark;
    }

    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MatchResult result && Objects.equals(id, result.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
