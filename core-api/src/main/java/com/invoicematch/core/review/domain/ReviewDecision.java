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

    protected ReviewDecision() {
    }

    private ReviewDecision(
            UUID id,
            UUID invoiceCaseId,
            UUID reviewSnapshotId,
            ReviewDecisionType decision,
            String decidedBy,
            String reason,
            String decisionPayload,
            String payloadHash,
            Instant decidedAt) {
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new DomainValidationException("decidedBy must not be blank");
        }
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new DomainValidationException("payloadHash must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.reviewSnapshotId = Objects.requireNonNull(reviewSnapshotId, "reviewSnapshotId");
        this.decision = Objects.requireNonNull(decision, "decision");
        this.decidedBy = decidedBy;
        this.reason = reason;
        this.decisionPayload = decisionPayload;
        this.payloadHash = payloadHash;
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public static ReviewDecision record(
            UUID id,
            UUID invoiceCaseId,
            UUID reviewSnapshotId,
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
                decision,
                decidedBy,
                reason,
                decisionPayload,
                payloadHash,
                decidedAt);
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

    @Override
    public boolean equals(Object other) {
        return other instanceof ReviewDecision decisionRecord && Objects.equals(id, decisionRecord.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
