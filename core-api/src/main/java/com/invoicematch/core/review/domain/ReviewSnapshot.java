package com.invoicematch.core.review.domain;

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
 * Frozen approval subject. It captures the evidence version, match result,
 * mapping decisions, expected allocation and amounts shown to the approver,
 * together with the target versions and a payload hash. Approval binds to this
 * snapshot rather than to any optional AI proposal.
 *
 * <p>{@code snapshotNumber} is the unambiguous per-case business order (the
 * database enforces uniqueness), so latest/history never depend on timestamps
 * or random identifiers. The purchasing snapshot version/hash and the mapping
 * watermark are captured as columns so freshness for a later approval can be
 * checked without reading the canonical JSON.
 */
@Entity
@Table(name = "review_snapshot")
@Immutable
public class ReviewSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "evidence_bundle_id", nullable = false, updatable = false)
    private UUID evidenceBundleId;

    @Column(name = "match_result_id", updatable = false)
    private UUID matchResultId;

    @Column(name = "match_result_number", updatable = false)
    private Integer matchResultNumber;

    @Column(name = "snapshot_number", nullable = false, updatable = false)
    private int snapshotNumber;

    @Column(name = "target_case_version", nullable = false, updatable = false)
    private long targetCaseVersion;

    @Column(name = "target_evidence_bundle_version", nullable = false, updatable = false)
    private int targetEvidenceBundleVersion;

    @Column(name = "purchasing_snapshot_version", nullable = false, updatable = false)
    private long purchasingSnapshotVersion;

    @Column(name = "purchasing_snapshot_hash", nullable = false, updatable = false, length = 128)
    private String purchasingSnapshotHash;

    @Column(name = "mapping_watermark", nullable = false, updatable = false)
    private int mappingWatermark;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 128)
    private String payloadHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReviewSnapshot() {
    }

    private ReviewSnapshot(
            UUID id,
            UUID invoiceCaseId,
            UUID evidenceBundleId,
            UUID matchResultId,
            Integer matchResultNumber,
            int snapshotNumber,
            long targetCaseVersion,
            int targetEvidenceBundleVersion,
            long purchasingSnapshotVersion,
            String purchasingSnapshotHash,
            int mappingWatermark,
            String payloadHash,
            String payload,
            Instant createdAt) {
        if (snapshotNumber <= 0) {
            throw new DomainValidationException("snapshotNumber must be positive: " + snapshotNumber);
        }
        if (matchResultNumber != null && matchResultNumber <= 0) {
            throw new DomainValidationException("matchResultNumber must be positive: " + matchResultNumber);
        }
        if ((matchResultId == null) != (matchResultNumber == null)) {
            throw new DomainValidationException(
                    "matchResultId and matchResultNumber must both be present or both absent");
        }
        if (targetCaseVersion < 0) {
            throw new DomainValidationException("targetCaseVersion must not be negative: " + targetCaseVersion);
        }
        if (targetEvidenceBundleVersion <= 0) {
            throw new DomainValidationException(
                    "targetEvidenceBundleVersion must be positive: " + targetEvidenceBundleVersion);
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
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new DomainValidationException("payloadHash must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.evidenceBundleId = Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        this.matchResultId = matchResultId;
        this.matchResultNumber = matchResultNumber;
        this.snapshotNumber = snapshotNumber;
        this.targetCaseVersion = targetCaseVersion;
        this.targetEvidenceBundleVersion = targetEvidenceBundleVersion;
        this.purchasingSnapshotVersion = purchasingSnapshotVersion;
        this.purchasingSnapshotHash = purchasingSnapshotHash;
        this.mappingWatermark = mappingWatermark;
        this.payloadHash = payloadHash;
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ReviewSnapshot freeze(
            UUID id,
            UUID invoiceCaseId,
            UUID evidenceBundleId,
            UUID matchResultId,
            Integer matchResultNumber,
            int snapshotNumber,
            long targetCaseVersion,
            int targetEvidenceBundleVersion,
            long purchasingSnapshotVersion,
            String purchasingSnapshotHash,
            int mappingWatermark,
            String payloadHash,
            String payload,
            Instant createdAt) {
        return new ReviewSnapshot(
                id,
                invoiceCaseId,
                evidenceBundleId,
                matchResultId,
                matchResultNumber,
                snapshotNumber,
                targetCaseVersion,
                targetEvidenceBundleVersion,
                purchasingSnapshotVersion,
                purchasingSnapshotHash,
                mappingWatermark,
                payloadHash,
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

    public UUID matchResultId() {
        return matchResultId;
    }

    public Integer matchResultNumber() {
        return matchResultNumber;
    }

    public int snapshotNumber() {
        return snapshotNumber;
    }

    public long targetCaseVersion() {
        return targetCaseVersion;
    }

    public int targetEvidenceBundleVersion() {
        return targetEvidenceBundleVersion;
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

    public String payloadHash() {
        return payloadHash;
    }

    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReviewSnapshot snapshot && Objects.equals(id, snapshot.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
