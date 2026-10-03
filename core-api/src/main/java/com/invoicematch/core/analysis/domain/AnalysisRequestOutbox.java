package com.invoicematch.core.analysis.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The transactional Outbox row for one analysis run, kept separate from the
 * payment {@code outbox_event}. The run id, schema discriminator and the
 * canonical JSON payload are fixed at creation and never change.
 *
 * <p>V13 adds the relay lifecycle. This entity is the creation-side mapping: a
 * reservation starts {@code READY} with {@code attemptCount = 0} and is due
 * immediately ({@code nextAttemptAt = createdAt}). Claiming, publishing,
 * releasing and cancelling are conditional DB updates owned by the persistence
 * store, never entity dirty checking, so the relay can never overwrite a
 * CLAIMED/PUBLISHED row by saving a loaded entity.
 */
@Entity
@Table(name = "analysis_request_outbox")
public class AnalysisRequestOutbox {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analysis_run_id", nullable = false, updatable = false)
    private UUID analysisRunId;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 32)
    private String schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AnalysisRequestStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AnalysisRequestOutbox() {
    }

    private AnalysisRequestOutbox(UUID id, UUID analysisRunId, String schemaVersion, String payload, Instant now) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new DomainValidationException("analysis request schemaVersion must not be blank");
        }
        if (payload == null || payload.isBlank()) {
            throw new DomainValidationException("analysis request payload must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.analysisRunId = Objects.requireNonNull(analysisRunId, "analysisRunId");
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.status = AnalysisRequestStatus.READY;
        this.attemptCount = 0;
        this.nextAttemptAt = Objects.requireNonNull(now, "now");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /** Reserves a {@code READY} request that is due immediately. */
    public static AnalysisRequestOutbox requested(
            UUID id, UUID analysisRunId, String schemaVersion, String payload, Instant now) {
        return new AnalysisRequestOutbox(id, analysisRunId, schemaVersion, payload, now);
    }

    public UUID id() {
        return id;
    }

    public UUID analysisRunId() {
        return analysisRunId;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String payload() {
        return payload;
    }

    public AnalysisRequestStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public UUID claimToken() {
        return claimToken;
    }

    public Instant leaseUntil() {
        return leaseUntil;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AnalysisRequestOutbox outbox && Objects.equals(id, outbox.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
