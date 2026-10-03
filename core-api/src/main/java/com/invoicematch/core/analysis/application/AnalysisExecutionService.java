package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.persistence.AnalysisRecoveryStore;
import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import com.invoicematch.core.analysis.persistence.AnalysisDocumentResultStore;
import com.invoicematch.core.analysis.persistence.AnalysisExecutionStore;
import com.invoicematch.core.analysis.persistence.AnalysisRunSnapshot;
import com.invoicematch.core.analysis.persistence.StoredDocumentResult;
import com.invoicematch.core.document.domain.DocumentEvidence;
import com.invoicematch.core.document.application.DocumentOriginalRequest;
import com.invoicematch.core.document.persistence.DocumentStore;
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
    private final AnalysisManifestVerifier manifestVerifier;
    private final AnalysisResultValidator validator;
    private final AnalysisExecutionProperties properties;
    private final Clock clock;
    private final DocumentStore documents;
    private final AnalysisRecoveryService recovery;
    private final AnalysisRecoveryStore recoveryStore;

    public AnalysisExecutionService(
            AnalysisExecutionStore execution,
            AnalysisDocumentResultStore results,
            AnalysisManifestVerifier manifestVerifier,
            AnalysisResultValidator validator,
            AnalysisExecutionProperties properties,
            Clock clock, DocumentStore documents, AnalysisRecoveryService recovery,
            AnalysisRecoveryStore recoveryStore) {
        this.recovery=recovery; this.recoveryStore=recoveryStore;
        this.execution = execution;
        this.results = results;
        this.manifestVerifier = manifestVerifier;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
        this.documents = documents;
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
            case COMPLETED, FAILED, DEAD_LETTERED -> new ClaimOutcome.AlreadyFinished(run.status());
            case RUNNING -> {
                if (run.leaseActive()) yield new ClaimOutcome.Busy(run.leaseUntil());
                if (run.executionAttempt() >= run.attemptLimit()) {
                    recovery.exhausted(run);
                    yield new ClaimOutcome.AlreadyFinished(AnalysisRunStatus.DEAD_LETTERED);
                }
                yield claim(run);
            }
            case RETRY_SCHEDULED -> {
                var due=recoveryStore.pendingDeadline(runId);
                if (due.isEmpty()) throw conflict("LEASE_CONFLICT", "missing durable recovery checkpoint");
                if (due.isPresent() && !recoveryStore.retryDue(runId)) yield new ClaimOutcome.Busy(due.get());
                yield claim(run);
            }
            case QUEUED -> claim(run);
        };
    }

    @Transactional
    public DocumentOriginalRequest authorizeSource(UUID runId, UUID documentId, AnalysisHeartbeatCommand command) {
        AnalysisRunSnapshot run = lock(runId);
        if (command.claimToken() == null || command.inputVersion() == null
                || isBlank(command.evidencePayloadHash())) {
            throw invalid("VALIDATION_ERROR", "claimToken, inputVersion and evidencePayloadHash are required");
        }
        requireRunInput(run, command.inputVersion(), command.evidencePayloadHash());
        requireActiveClaim(run, command.claimToken());
        DocumentEvidence frozen = manifestVerifier.verify(run).stream()
                .filter(document -> document.documentId().equals(documentId)).findFirst()
                .orElseThrow(() -> conflict("MANIFEST_MISMATCH", "document is not part of the frozen manifest"));
        var registered = documents.document(run.invoiceCaseId(), documentId)
                .orElseThrow(() -> conflict("MANIFEST_MISMATCH", "registered document is unavailable"));
        var upload = registered.upload();
        if (!upload.draftRevisionId().equals(frozen.sourceDraftRevisionId())
                || !upload.fileName().equals(frozen.fileName()) || !upload.mediaType().equals(frozen.mediaType())
                || upload.sizeBytes() != frozen.sizeBytes() || !upload.checksum().equals(frozen.checksum())) {
            throw conflict("MANIFEST_MISMATCH", "registered metadata differs from the frozen manifest");
        }
        return new DocumentOriginalRequest(registered.objectKey(), frozen.mediaType(), frozen.sizeBytes(), frozen.checksum());
    }

    @Transactional
    public HeartbeatOutcome heartbeat(UUID runId, AnalysisHeartbeatCommand command) {
        AnalysisRunSnapshot run = lock(runId);
        if (command.claimToken() == null || command.inputVersion() == null
                || isBlank(command.evidencePayloadHash())) {
            throw invalid("VALIDATION_ERROR", "claimToken, inputVersion and evidencePayloadHash are required");
        }
        requireRunInput(run, command.inputVersion(), command.evidencePayloadHash());
        requireActiveClaim(run, command.claimToken());
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
        // The run input identity is authoritative before the manifest, the
        // validator, the replay branch or any active-token check: a mismatched
        // input can never be replayed as if it belonged to this run.
        requireRunInput(run, command.inputVersion(), command.evidencePayloadHash());

        List<DocumentEvidence> documents = manifestVerifier.verify(run);
        DocumentEvidence document = documents.stream()
                .filter(candidate -> candidate.documentId().equals(command.documentId()))
                .findFirst()
                .orElseThrow(() -> conflict("MANIFEST_MISMATCH",
                        "the result document does not belong to the frozen manifest"));
        ValidatedDocumentResult validated = validator.validate(command, document);

        Optional<StoredDocumentResult> existing = results.find(runId, command.documentId());
        if (existing.isPresent()) {
            // Replay only needs the matching canonical hash; an expired or old
            // token and a terminal run are allowed.
            if (existing.get().payloadHash().equals(validated.payloadHash())) {
                return new ResultOutcome.Replayed(run.status());
            }
            throw conflict("RESULT_CONFLICT", "a different result is already stored for this document");
        }

        requireActiveClaim(run, command.claimToken());
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
        List<DocumentEvidence> documents = manifestVerifier.verify(run);
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

    private void requireRunInput(AnalysisRunSnapshot run, int inputVersion, String evidencePayloadHash) {
        if (run.inputVersion() != inputVersion || !run.evidencePayloadHash().equals(evidencePayloadHash)) {
            throw conflict("INPUT_MISMATCH", "the request does not match the frozen run identity");
        }
    }

    private void requireActiveClaim(AnalysisRunSnapshot run, UUID claimToken) {
        if (run.status() != AnalysisRunStatus.RUNNING
                || run.executionToken() == null
                || !run.executionToken().equals(claimToken)
                || !run.leaseActive()) {
            throw conflict("LEASE_CONFLICT", "no active claim for the supplied token");
        }
    }

    private AnalysisRunSnapshot lock(UUID runId) {
        return execution.lockByRunId(runId)
                .orElseThrow(() -> new AnalysisRunNotFoundException(runId));
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
