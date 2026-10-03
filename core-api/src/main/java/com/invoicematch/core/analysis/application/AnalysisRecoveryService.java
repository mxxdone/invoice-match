package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import com.invoicematch.core.analysis.persistence.AnalysisExecutionStore;
import com.invoicematch.core.analysis.persistence.AnalysisRecoveryStore;
import com.invoicematch.core.analysis.persistence.AnalysisRunSnapshot;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fenced, idempotent failure checkpoint. No external I/O inside these transactions. */
@Service
public class AnalysisRecoveryService {
    private static final Set<String> TRANSIENT = Set.of("CORE_UNAVAILABLE", "CORE_TRANSIENT", "LEASE_TOO_SHORT");
    private static final Set<String> PERMANENT = Set.of("CORE_REQUEST_FAILED", "INVALID_PROTOCOL", "SOURCE_MISMATCH",
            "RESPONSE_TOO_LARGE", "CLAIM_MISMATCH", "INVALID_PARSER_FAILURE", "NO_TERMINAL_RESULT", "WORKER_FAILED");
    private final AnalysisExecutionStore execution;
    private final AnalysisRecoveryStore store;
    public AnalysisRecoveryService(AnalysisExecutionStore execution, AnalysisRecoveryStore store) {
        this.execution=execution; this.store=store;
    }
    public record Checkpoint(String disposition, String runStatus) {}
    private Checkpoint response(AnalysisRunStatus status) { return new Checkpoint("CHECKPOINTED",status.name()); }

    @Transactional
    public Checkpoint failure(UUID id, AnalysisFailureCommand command) {
        var run = lock(id);
        if (command.claimToken()==null || command.inputVersion()==null || command.errorCode()==null
                || !(TRANSIENT.contains(command.errorCode()) || PERMANENT.contains(command.errorCode())))
            throw new AnalysisValidationException("VALIDATION_ERROR","Invalid failure checkpoint");
        input(run,command.inputVersion(),command.evidencePayloadHash());
        if (terminal(run)) return response(run.status());
        var previous=store.failure(id,command.claimToken());
        if (previous.isPresent()) return new Checkpoint("CHECKPOINTED",previous.get());
        if (run.status()!=AnalysisRunStatus.RUNNING || !command.claimToken().equals(run.executionToken()))
            throw new AnalysisConflictException("LEASE_CONFLICT","No matching execution claim");
        return checkpoint(run,command.errorCode(),TRANSIENT.contains(command.errorCode()));
    }

    @Transactional
    public Checkpoint defer(UUID id, AnalysisClaimCommand command) {
        var run=lock(id);
        if (command.eventId()==null || command.inputVersion()==null
                || !execution.findRequestEventId(id).filter(command.eventId()::equals).isPresent()
                || !run.workflowVersion().equals(command.workflowVersion()))
            throw new AnalysisValidationException("VALIDATION_ERROR","Invalid deferred request");
        input(run,command.inputVersion(),command.evidencePayloadHash());
        if (terminal(run)) return response(run.status());
        if (run.status()==AnalysisRunStatus.RETRY_SCHEDULED) {
            if (store.pendingDeadline(id).isEmpty()) throw new IllegalStateException("Missing retry checkpoint");
            return response(run.status());
        }
        String key=run.status()==AnalysisRunStatus.RUNNING ? "defer:"+run.executionToken() : "queued:"+run.executionAttempt();
        store.dispatch(id,key,"REQUEST",Duration.ofSeconds(1),true);
        return response(run.status());
    }

    /** Called by claim after locking the same case/run and only when its lease expired. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Checkpoint exhausted(AnalysisRunSnapshot run) { return checkpoint(run,"LEASE_EXPIRED",false); }

    private Checkpoint checkpoint(AnalysisRunSnapshot run,String code,boolean retryable) {
        boolean retry=retryable && run.executionAttempt()<run.attemptLimit();
        int roundAttempt=run.executionAttempt()-(run.attemptLimit()-3);
        long base=Math.min(60,5L << Math.min(3,Math.max(0,roundAttempt-1)));
        var delay=retry ? Duration.ofMillis(base*1000+ThreadLocalRandom.current().nextLong(base*500+1)) : Duration.ZERO;
        var status=retry ? AnalysisRunStatus.RETRY_SCHEDULED : AnalysisRunStatus.DEAD_LETTERED;
        store.checkpoint(run,code,status.name(),retry ? "REQUEST" : "DLQ",delay);
        return response(status);
    }
    private boolean terminal(AnalysisRunSnapshot r) {
        return Set.of(AnalysisRunStatus.STALE,AnalysisRunStatus.COMPLETED,AnalysisRunStatus.FAILED,AnalysisRunStatus.DEAD_LETTERED).contains(r.status());
    }
    private void input(AnalysisRunSnapshot run,int version,String hash) {
        if (run.inputVersion()!=version || !run.evidencePayloadHash().equals(hash))
            throw new AnalysisConflictException("INPUT_MISMATCH","Frozen input mismatch");
    }
    private AnalysisRunSnapshot lock(UUID id) { return execution.lockByRunId(id).orElseThrow(()->new AnalysisRunNotFoundException(id)); }
}
