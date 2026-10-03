package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import com.invoicematch.core.analysis.persistence.AnalysisDocumentResultStore;
import com.invoicematch.core.analysis.persistence.AnalysisExecutionStore;
import com.invoicematch.core.analysis.persistence.AnalysisRunSnapshot;
import com.invoicematch.core.analysis.persistence.StoredDocumentResult;
import com.invoicematch.core.document.domain.DocumentEvidence;
import com.invoicematch.core.document.persistence.DocumentStore;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the analysis execution policy: validates the machine input against the
 * authoritative run and frozen manifest, decides the claim/heartbeat/result
 * disposition, and coordinates the per-document result store with the terminal
 * transition in one transaction. Persistence owns the SQL, locking and save;
 * this service never touches JDBC directly and never builds an HTTP shape.
 *
 * <p>Every method runs in a short transaction and locks {@code invoice_case} then
 * {@code analysis_run}, the same order submission takes, so a superseding
 * submission and an in-flight result can never interleave. No storage, broker or
 * external HTTP call happens inside the transaction.
 */
@Service
@EnableConfigurationProperties({AnalysisExecutionProperties.class, AnalysisWorkerProperties.class})
public class AnalysisExecutionService {

    private final AnalysisExecutionStore execution;
    private final AnalysisDocumentResultStore results;
    private final EvidenceBundleRepository evidenceBundles;
    private final DocumentStore documentEvidence;
    private final AnalysisResultValidator validator;
    private final AnalysisExecutionProperties properties;
    private final Clock clock;

    public AnalysisExecutionService(
            AnalysisExecutionStore execution,
            AnalysisDocumentResultStore results,
            EvidenceBundleRepository evidenceBundles,
            DocumentStore documentEvidence,
            AnalysisResultValidator validator,
            AnalysisExecutionProperties properties,
            Clock clock) {
        this.execution = execution;
        this.results = results;
        this.evidenceBundles = evidenceBundles;
        this.documentEvidence = documentEvidence;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ClaimOutcome claim(UUID runId, AnalysisClaimCommand command) {
        AnalysisRunSnapshot run = lock(runId);
        if (command.eventId() == null || command.inputVersion() == null || command.inputVersion() <= 0
                || isBlank(command.evidencePayloadHash()) || isBlank(command.workflowVersion())) {
            throw invalid("VALIDATION_ERROR", "eventId, inputVersion, evidencePayloadHash and"
                    + " workflowVersion are required");
        }
        UUID eventId = execution.findRequestEventId(runId)
                .orElseThrow(() -> conflict("MANIFEST_MISMATCH", "no frozen request is connected to the run"));
        if (!eventId.equals(command.eventId())
                || command.inputVersion() != run.inputVersion()
                || !run.evidencePayloadHash().equals(command.evidencePayloadHash())
                || !run.workflowVersion().equals(command.workflowVersion())) {
            throw conflict("INPUT_MISMATCH", "the claim does not match the frozen request identity");
        }

        return switch (run.status()) {
            case STALE -> new ClaimOutcome.Stale();
            case COMPLETED, FAILED -> new ClaimOutcome.AlreadyFinished(run.status());
            case RUNNING -> run.leaseActive() ? new ClaimOutcome.Busy(run.leaseUntil()) : claim(run);
            case QUEUED -> claim(run);
        };
    }

    @Transactional
    public HeartbeatOutcome heartbeat(UUID runId, AnalysisHeartbeatCommand command) {
        AnalysisRunSnapshot run = lock(runId);
        if (command.claimToken() == null || command.inputVersion() == null
                || isBlank(command.evidencePayloadHash())) {
            throw invalid("VALIDATION_ERROR", "claimToken, inputVersion and evidencePayloadHash are required");
        }
        requireActiveClaim(run, command.claimToken(), command.inputVersion(), command.evidencePayloadHash());
        Instant leaseUntil = execution.heartbeat(runId, command.claimToken(), properties.leaseDuration())
                .orElseThrow(() -> conflict("LEASE_CONFLICT", "the execution lease is no longer active"));
        return new HeartbeatOutcome(leaseUntil);
    }

    @Transactional
    public ResultOutcome recordResult(UUID runId, AnalysisDocumentResultCommand command) {
        AnalysisRunSnapshot run = lock(runId);
        if (command.claimToken() == null || command.inputVersion() == null || isBlank(command.evidencePayloadHash())
                || command.documentId() == null || isBlank(command.outcome())) {
            throw invalid("VALIDATION_ERROR",
                    "claimToken, inputVersion, evidencePayloadHash, documentId and outcome are required");
        }
        // Superseded runs ignore results without adding a row; the record of the
        // run and any prior results is preserved.
        if (run.status() == AnalysisRunStatus.STALE) {
            return new ResultOutcome.Stale();
        }

        List<DocumentEvidence> documents = frozenDocuments(run);
        DocumentEvidence document = documents.stream()
                .filter(candidate -> candidate.documentId().equals(command.documentId()))
                .findFirst()
                .orElseThrow(() -> conflict("MANIFEST_MISMATCH",
                        "the result document does not belong to the frozen manifest"));
        ValidatedDocumentResult validated = validator.validate(command, document);

        Optional<StoredDocumentResult> existing = results.find(runId, command.documentId());
        if (existing.isPresent()) {
            if (existing.get().payloadHash().equals(validated.payloadHash())) {
                return new ResultOutcome.Replayed(run.status());
            }
            throw conflict("RESULT_CONFLICT", "a different result is already stored for this document");
        }

        requireActiveClaim(run, command.claimToken(), command.inputVersion(), command.evidencePayloadHash());
        results.insert(
                runId,
                command.documentId(),
                document.checksum(),
                validated.outcome(),
                validated.parserVersion(),
                validated.resultSchemaVersion(),
                validated.payloadJson(),
                validated.errorCode(),
                validated.payloadHash(),
                clock.instant());

        List<StoredDocumentResult> stored = results.findByRunId(runId);
        if (stored.size() == documents.size()) {
            boolean anyFailure = stored.stream().anyMatch(result -> "FAILURE".equals(result.outcome()));
            AnalysisRunStatus terminal = anyFailure ? AnalysisRunStatus.FAILED : AnalysisRunStatus.COMPLETED;
            if (!execution.finalizeTerminal(runId, command.claimToken(), terminal)) {
                throw conflict("LEASE_CONFLICT", "the execution lease was lost before the run could finish");
            }
            return new ResultOutcome.Accepted(terminal);
        }
        return new ResultOutcome.Accepted(AnalysisRunStatus.RUNNING);
    }

    private ClaimOutcome claim(AnalysisRunSnapshot run) {
        List<DocumentEvidence> documents = frozenDocuments(run);
        UUID token = UUID.randomUUID();
        Instant leaseUntil = execution.claim(run.runId(), token, properties.leaseDuration())
                .orElseThrow(() -> conflict("LEASE_CONFLICT", "the run is no longer claimable"));
        return new ClaimOutcome.Claimed(
                token,
                leaseUntil,
                run.invoiceCaseId(),
                run.evidenceBundleId(),
                run.inputVersion(),
                run.evidencePayloadHash(),
                run.workflowVersion(),
                documents);
    }

    private void requireActiveClaim(
            AnalysisRunSnapshot run, UUID claimToken, int inputVersion, String evidencePayloadHash) {
        if (run.status() != AnalysisRunStatus.RUNNING
                || run.executionToken() == null
                || !run.executionToken().equals(claimToken)
                || !run.leaseActive()) {
            throw conflict("LEASE_CONFLICT", "no active claim for the supplied token");
        }
        if (run.inputVersion() != inputVersion || !run.evidencePayloadHash().equals(evidencePayloadHash)) {
            throw conflict("INPUT_MISMATCH", "the request does not match the frozen run identity");
        }
    }

    private AnalysisRunSnapshot lock(UUID runId) {
        return execution.lockByRunId(runId)
                .orElseThrow(() -> new AnalysisRunNotFoundException(runId));
    }

    /**
     * The authoritative frozen manifest for the run: the immutable document
     * metadata of the sealed revision the run's bundle froze, ascending by
     * document id. A run only exists for a document-bearing bundle, so an empty
     * manifest is a conflict rather than an empty execution.
     */
    private List<DocumentEvidence> frozenDocuments(AnalysisRunSnapshot run) {
        EvidenceBundle bundle = evidenceBundles.findById(run.evidenceBundleId())
                .orElseThrow(() -> conflict("MANIFEST_MISMATCH", "the frozen evidence bundle no longer exists"));
        if (!bundle.invoiceCaseId().equals(run.invoiceCaseId())) {
            throw conflict("MANIFEST_MISMATCH", "the frozen evidence bundle does not belong to the run case");
        }
        List<DocumentEvidence> documents = documentEvidence.evidenceForRevision(bundle.draftRevisionId());
        if (documents.isEmpty()) {
            throw conflict("MANIFEST_MISMATCH", "the frozen evidence bundle has no document references");
        }
        return documents;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static AnalysisValidationException invalid(String code, String message) {
        return new AnalysisValidationException(code, message);
    }

    private static AnalysisConflictException conflict(String code, String message) {
        return new AnalysisConflictException(code, message);
    }
}
