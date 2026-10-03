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
 * canonical JSON payload are fixed at creation and never change; a superseded
 * reservation only moves a {@code READY} request to {@code CANCELLED}. The
 * lease, sending and relay fields are P2-06.
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
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /** Reserves a {@code READY} request carrying the canonical payload. */
    public static AnalysisRequestOutbox requested(
            UUID id, UUID analysisRunId, String schemaVersion, String payload, Instant now) {
        return new AnalysisRequestOutbox(id, analysisRunId, schemaVersion, payload, now);
    }

    /** Cancels a superseded {@code READY} request so it can never be published. */
    public void cancel() {
        if (status != AnalysisRequestStatus.READY) {
            throw new DomainValidationException("analysis request " + id + " is already " + status);
        }
        this.status = AnalysisRequestStatus.CANCELLED;
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
