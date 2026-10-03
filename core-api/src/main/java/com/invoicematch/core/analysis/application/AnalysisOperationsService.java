package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import com.invoicematch.core.analysis.persistence.AnalysisExecutionStore;
import com.invoicematch.core.analysis.persistence.AnalysisOperationsStore;
import com.invoicematch.core.analysis.persistence.AnalysisRecoveryStore;
import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisOperationsService {
    private final AuthorizationService authorization;
    private final AnalysisOperationsStore store;
    private final AnalysisExecutionStore execution;
    private final AnalysisRecoveryStore recovery;
    private final AnalysisManifestVerifier manifest;
    private final RequestIdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    public AnalysisOperationsService(AuthorizationService authorization,AnalysisOperationsStore store,
            AnalysisExecutionStore execution,AnalysisRecoveryStore recovery,AnalysisManifestVerifier manifest,
            RequestIdempotencyStore idempotency,AuditRecorder audit,ObjectMapper mapper,Clock clock) {
        this.authorization=authorization;this.store=store;this.execution=execution;this.recovery=recovery;
        this.manifest=manifest;this.idempotency=idempotency;this.audit=audit;this.mapper=mapper;this.clock=clock;
    }
    public record Page<T>(List<T> items,int page,int size,long totalElements) {}
    public record RetryCommand(String requestId,Integer expectedExecutionAttempt,String reason) {}
    public record RetryResponse(UUID runId,String status,int executionAttempt,int attemptLimit) {}
    private void page(int page,int size) {
        if(page<0 || page>10000 || size<1 || size>100)
            throw new AnalysisValidationException("VALIDATION_ERROR","Invalid page bounds");
    }
    @Transactional(readOnly=true)
    public Page<AnalysisOperationsStore.Job> jobs(String status,int page,int size) {
        authorization.requireRole(Role.OPERATOR);page(page,size);
        if(status!=null) {
            try { AnalysisRunStatus.valueOf(status); }
            catch(IllegalArgumentException e) { throw new AnalysisValidationException("VALIDATION_ERROR","Invalid analysis status"); }
        }
        return new Page<>(store.jobs(status,page*size,size),page,size,store.count(status));
    }
    @Transactional(readOnly=true)
    public Page<AnalysisOperationsStore.Failure> failures(UUID run,int page,int size) {
        authorization.requireRole(Role.OPERATOR);page(page,size);
        if(!store.exists(run)) throw new AnalysisRunNotFoundException(run);
        return new Page<>(store.failures(run,page*size,size),page,size,store.failureCount(run));
    }
    @Transactional
    public CommandResult<RetryResponse> retry(UUID id,RetryCommand command) {
        authorization.requireRole(Role.OPERATOR);
        if(command.requestId()==null || command.requestId().length()>80 || command.requestId().isBlank()
                || command.expectedExecutionAttempt()==null || command.expectedExecutionAttempt()<1
                || command.reason()==null || command.reason().isBlank() || command.reason().length()>300)
            throw new AnalysisValidationException("VALIDATION_ERROR","Request id, expected attempt and repair reason are required");
        var actor=authorization.actor();
        var run=execution.lockByRunId(id).orElseThrow(()->new AnalysisRunNotFoundException(id));
        var body=mapper.createObjectNode().put("expectedExecutionAttempt",command.expectedExecutionAttempt()).put("reason",command.reason());
        String scope="analysis:retry",resource=id.toString();
        var begin=idempotency.begin(scope,resource,actor.username(),command.requestId(),hash(body.toString()));
        if(begin instanceof RequestIdempotencyStore.BeginResult.Replay replay)
            return new CommandResult<>(replay.response().status(),idempotency.decode(replay.response(),RetryResponse.class));
        if(run.status()!=AnalysisRunStatus.DEAD_LETTERED || run.executionAttempt()!=command.expectedExecutionAttempt()
                || store.latestInputVersion(run.invoiceCaseId())!=run.inputVersion())
            throw new AnalysisConflictException("RETRY_CONFLICT","Only the current exhausted execution can be retried");
        manifest.verify(run);
        store.reserveRetry(id,run.executionAttempt());
        recovery.dispatch(id,"operator:"+hash(actor.username()+"\n"+command.requestId()),"REQUEST",Duration.ZERO,false);
        audit.record(new AuditEvent(run.invoiceCaseId(),actor,AuditAction.ANALYSIS_RETRY_RESERVED,AuditTargetType.CASE,
                run.invoiceCaseId().toString(),store.caseVersion(run.invoiceCaseId()),Map.of("status",run.status().name()),
                Map.of("runId",id,"inputVersion",run.inputVersion(),"executionAttempt",run.executionAttempt(),
                    "attemptLimit",run.executionAttempt()+3,"reason",command.reason()),command.requestId(),clock.instant()));
        var response=new RetryResponse(id,"QUEUED",run.executionAttempt(),run.executionAttempt()+3);
        idempotency.recordResponse(scope,resource,actor.username(),command.requestId(),200,response);
        return new CommandResult<>(200,response);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
