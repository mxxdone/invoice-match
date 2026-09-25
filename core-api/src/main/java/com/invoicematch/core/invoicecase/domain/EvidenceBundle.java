package com.invoicematch.core.invoicecase.domain;

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
 * A submitted version of the evidence behind a claim. The manual input or
 * document set is frozen at submission time and can never be modified; a
 * correction creates the next version instead. {@code payloadHash} binds the
 * frozen JSON payload to later review and approval checks.
 */
@Entity
@Table(name = "evidence_bundle")
@Immutable
public class EvidenceBundle {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 128)
    private String payloadHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    protected EvidenceBundle() {
    }

    private EvidenceBundle(
            UUID id, UUID invoiceCaseId, int versionNumber, String payloadHash, String payload, Instant submittedAt) {
        if (versionNumber <= 0) {
            throw new DomainValidationException("Evidence bundle version must be positive: " + versionNumber);
        }
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new DomainValidationException("payloadHash must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.versionNumber = versionNumber;
        this.payloadHash = payloadHash;
        this.payload = Objects.requireNonNull(payload, "payload");
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
    }

    public static EvidenceBundle freeze(
            UUID id, UUID invoiceCaseId, int versionNumber, String payloadHash, String payload, Instant submittedAt) {
        return new EvidenceBundle(id, invoiceCaseId, versionNumber, payloadHash, payload, submittedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID invoiceCaseId() {
        return invoiceCaseId;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public String payloadHash() {
        return payloadHash;
    }

    public String payload() {
        return payload;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EvidenceBundle bundle && Objects.equals(id, bundle.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
