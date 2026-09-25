package com.invoicematch.core.invoicecase.domain;

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

/**
 * An editable revision of an invoice's manual input. Only one revision is
 * {@code OPEN} per claim at a time; a submitted claim seals its revision before
 * freezing the content into an immutable {@code EvidenceBundle}. This baseline
 * models the relationship and sealing rule but does not implement submission.
 */
@Entity
@Table(name = "draft_revision")
public class DraftRevision {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DraftRevisionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    protected DraftRevision() {
    }

    private DraftRevision(UUID id, UUID invoiceCaseId, int revisionNumber, Instant now) {
        if (revisionNumber <= 0) {
            throw new DomainValidationException("Draft revision number must be positive: " + revisionNumber);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.revisionNumber = revisionNumber;
        this.status = DraftRevisionStatus.OPEN;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public static DraftRevision open(UUID invoiceCaseId, int revisionNumber, Instant now) {
        return new DraftRevision(UUID.randomUUID(), invoiceCaseId, revisionNumber, now);
    }

    /**
     * Seals this revision so its content can no longer be edited.
     */
    public void seal(Instant occurredAt) {
        if (status == DraftRevisionStatus.SEALED) {
            throw new DomainValidationException("Draft revision " + id + " is already sealed");
        }
        this.status = DraftRevisionStatus.SEALED;
        this.sealedAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public boolean isEditable() {
        return status == DraftRevisionStatus.OPEN;
    }

    public UUID id() {
        return id;
    }

    public UUID invoiceCaseId() {
        return invoiceCaseId;
    }

    public int revisionNumber() {
        return revisionNumber;
    }

    public DraftRevisionStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant sealedAt() {
        return sealedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DraftRevision revision && Objects.equals(id, revision.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
