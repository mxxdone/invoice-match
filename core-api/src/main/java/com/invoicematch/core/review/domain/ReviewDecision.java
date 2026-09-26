package com.invoicematch.core.review.domain;

import com.invoicematch.core.shared.domain.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An immutable record of a human decision against a specific review snapshot
 * version. It stores who decided, what they decided, the reason and the
 * modified values, together with the hash of the snapshot it targeted.
 *
 * <p>{@code decisionNumber} is the per-case monotonic append order. A
 * {@code MAPPING} decision additionally carries normalized mapping fields
 * (target bundle, invoice line number, chosen item and the resolved purchase
 * order line) so effective mappings are queryable rather than parsed out of
 * JSON. Every other decision type leaves those fields null, which the database
 * enforces with a check constraint.
 */
@Entity
@Table(name = "review_decision")
@Immutable
public class ReviewDecision {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "review_snapshot_id", nullable = false, updatable = false)
    private UUID reviewSnapshotId;

    @Column(name = "decision_number", nullable = false, updatable = false)
    private int decisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 32)
    private ReviewDecisionType decision;

    @Column(name = "decided_by", nullable = false, updatable = false, length = 64)
    private String decidedBy;

    @Column(name = "reason", updatable = false, length = 1000)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_payload", updatable = false)
    private String decisionPayload;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 128)
    private String payloadHash;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @Column(name = "mapping_bundle_id", updatable = false)
    private UUID mappingBundleId;

    @Column(name = "mapping_line_number", updatable = false)
    private Integer mappingLineNumber;

    @Column(name = "mapping_item_id", updatable = false, length = 64)
    private String mappingItemId;

    @Column(name = "mapping_po_line_id", updatable = false, length = 64)
    private String mappingPoLineId;

    protected ReviewDecision() {
    }

    private ReviewDecision(
            UUID id,
            UUID invoiceCaseId,
            UUID reviewSnapshotId,
            int decisionNumber,
            ReviewDecisionType decision,
            String decidedBy,
            String reason,
            String decisionPayload,
            String payloadHash,
            Instant decidedAt,
            UUID mappingBundleId,
            Integer mappingLineNumber,
            String mappingItemId,
            String mappingPoLineId) {
        if (decisionNumber <= 0) {
            throw new DomainValidationException("decisionNumber must be positive: " + decisionNumber);
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new DomainValidationException("decidedBy must not be blank");
        }
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new DomainValidationException("payloadHash must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.reviewSnapshotId = Objects.requireNonNull(reviewSnapshotId, "reviewSnapshotId");
        this.decisionNumber = decisionNumber;
        this.decision = Objects.requireNonNull(decision, "decision");
        this.decidedBy = decidedBy;
        this.reason = reason;
        this.decisionPayload = decisionPayload;
        this.payloadHash = payloadHash;
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");

        if (decision == ReviewDecisionType.MAPPING) {
            if (mappingBundleId == null || mappingLineNumber == null || mappingItemId == null
                    || mappingPoLineId == null) {
                throw new DomainValidationException(
                        "A MAPPING decision requires bundle, line number, item and purchase order line");
            }
            if (mappingLineNumber <= 0) {
                throw new DomainValidationException("mappingLineNumber must be positive: " + mappingLineNumber);
            }
        } else if (mappingBundleId != null || mappingLineNumber != null || mappingItemId != null
                || mappingPoLineId != null) {
            throw new DomainValidationException(
                    "Only a MAPPING decision may carry mapping fields, but decision was " + decision);
        }
        this.mappingBundleId = mappingBundleId;
        this.mappingLineNumber = mappingLineNumber;
        this.mappingItemId = mappingItemId;
        this.mappingPoLineId = mappingPoLineId;
    }

    public static ReviewDecision record(
            UUID id,
            UUID invoiceCaseId,
            UUID reviewSnapshotId,
            int decisionNumber,
            ReviewDecisionType decision,
            String decidedBy,
            String reason,
            String decisionPayload,
            String payloadHash,
            Instant decidedAt) {
        return new ReviewDecision(
                id,
                invoiceCaseId,
                reviewSnapshotId,
                decisionNumber,
                decision,
                decidedBy,
                reason,
                decisionPayload,
                payloadHash,
                decidedAt,
                null,
                null,
                null,
                null);
    }

    public static ReviewDecision recordMapping(
            UUID id,
            UUID invoiceCaseId,
            UUID reviewSnapshotId,
            int decisionNumber,
            String decidedBy,
            String reason,
            String decisionPayload,
            String payloadHash,
            Instant decidedAt,
            UUID mappingBundleId,
            int mappingLineNumber,
            String mappingItemId,
            String mappingPoLineId) {
        return new ReviewDecision(
                id,
                invoiceCaseId,
                reviewSnapshotId,
                decisionNumber,
                ReviewDecisionType.MAPPING,
                decidedBy,
                reason,
                decisionPayload,
                payloadHash,
                decidedAt,
                mappingBundleId,
                mappingLineNumber,
                mappingItemId,
                mappingPoLineId);
    }

    public UUID id() {
        return id;
    }

    public UUID invoiceCaseId() {
        return invoiceCaseId;
    }

    public UUID reviewSnapshotId() {
        return reviewSnapshotId;
    }

    public int decisionNumber() {
        return decisionNumber;
    }

    public ReviewDecisionType decision() {
        return decision;
    }

    public String decidedBy() {
        return decidedBy;
    }

    public String reason() {
        return reason;
    }

    public String decisionPayload() {
        return decisionPayload;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public UUID mappingBundleId() {
        return mappingBundleId;
    }

    public Integer mappingLineNumber() {
        return mappingLineNumber;
    }

    public String mappingItemId() {
        return mappingItemId;
    }

    public String mappingPoLineId() {
        return mappingPoLineId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReviewDecision decisionRecord && Objects.equals(id, decisionRecord.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
