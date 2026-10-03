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

/**
 * The reservation of one parser-backed analysis execution for a frozen evidence
 * bundle version. Identity, frozen evidence payload hash and workflow version
 * are fixed at creation and never change; a newer bundle submission only moves
 * an existing reservation to {@link AnalysisRunStatus#STALE}. The parser output,
 * retries and result reflection are P2-06.
 */
@Entity
@Table(name = "analysis_run")
public class AnalysisRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_case_id", nullable = false, updatable = false)
    private UUID invoiceCaseId;

    @Column(name = "evidence_bundle_id", nullable = false, updatable = false)
    private UUID evidenceBundleId;

    @Column(name = "input_version", nullable = false, updatable = false)
    private int inputVersion;

    @Column(name = "evidence_payload_hash", nullable = false, updatable = false, length = 128)
    private String evidencePayloadHash;

    @Column(name = "workflow_version", nullable = false, updatable = false, length = 64)
    private String workflowVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AnalysisRunStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AnalysisRun() {
    }

    private AnalysisRun(
            UUID id,
            UUID invoiceCaseId,
            UUID evidenceBundleId,
            int inputVersion,
            String evidencePayloadHash,
            Instant now) {
        if (inputVersion <= 0) {
            throw new DomainValidationException("AnalysisRun inputVersion must be positive: " + inputVersion);
        }
        if (evidencePayloadHash == null || evidencePayloadHash.isBlank()) {
            throw new DomainValidationException("AnalysisRun evidencePayloadHash must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.invoiceCaseId = Objects.requireNonNull(invoiceCaseId, "invoiceCaseId");
        this.evidenceBundleId = Objects.requireNonNull(evidenceBundleId, "evidenceBundleId");
        this.inputVersion = inputVersion;
        this.evidencePayloadHash = evidencePayloadHash;
        this.workflowVersion = AnalysisWorkflow.VERSION;
        this.status = AnalysisRunStatus.QUEUED;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** Reserves a new {@code QUEUED} run for the frozen bundle version. */
    public static AnalysisRun queue(
            UUID id, UUID invoiceCaseId, UUID evidenceBundleId, int inputVersion, String evidencePayloadHash,
            Instant now) {
        return new AnalysisRun(id, invoiceCaseId, evidenceBundleId, inputVersion, evidencePayloadHash, now);
    }

    /**
     * Marks a superseded reservation stale. Only a live {@code QUEUED} run can
     * be staled; a second submission must never rewrite an already stale run.
     */
    public void markStale(Instant now) {
        if (status != AnalysisRunStatus.QUEUED) {
            throw new DomainValidationException("AnalysisRun " + id + " is already " + status);
        }
        this.status = AnalysisRunStatus.STALE;
        this.updatedAt = Objects.requireNonNull(now, "now");
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

    public int inputVersion() {
        return inputVersion;
    }

    public String evidencePayloadHash() {
        return evidencePayloadHash;
    }

    public String workflowVersion() {
        return workflowVersion;
    }

    public AnalysisRunStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AnalysisRun run && Objects.equals(id, run.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
