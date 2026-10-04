package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human intent reserves advisory work, without performing matching or any external I/O. */
@Service
@EnableConfigurationProperties(ProposalProperties.class)
public class ProposalService {
    private final ProposalProperties properties;
    private final AuthorizationService authorization;
    private final ProposalStore store;
    private final ProposalContextFactory contexts;
    private final RequestIdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final Clock clock;
    private final ObjectMapper mapper;
    public ProposalService(ProposalProperties properties, AuthorizationService authorization, ProposalStore store,
            ProposalContextFactory contexts, RequestIdempotencyStore idempotency, AuditRecorder audit,
            Clock clock, ObjectMapper mapper) {
        this.properties=properties;this.authorization=authorization;this.store=store;this.contexts=contexts;
        this.idempotency=idempotency;this.audit=audit;this.clock=clock;this.mapper=mapper;
    }
    public record ReserveCommand(String requestId, Long expectedCaseVersion) {}
    public record Reserved(UUID id, String status, String contextHash) {}
    @Transactional
    public CommandResult<Reserved> reserve(UUID caseId, ReserveCommand command) {
        authorization.requireRole(Role.OPERATOR);
        if(command==null || command.requestId()==null || command.requestId().isBlank() || command.requestId().length()>80
                || command.expectedCaseVersion()==null || command.expectedCaseVersion()<0)
            throw new AnalysisValidationException("VALIDATION_ERROR","Request id and case version are required");
        var state=store.lockCase(caseId).orElseThrow(()->new InvoiceCaseNotFoundException(caseId));
        var actor=authorization.actor(); String scope="proposal:reserve",resource=caseId.toString();
        var fingerprint=AnalysisCanonicalJson.sha256Hex(mapper.createObjectNode()
                .put("expectedCaseVersion",command.expectedCaseVersion()).toString());
        var begin=idempotency.begin(scope,resource,actor.username(),command.requestId(),fingerprint);
        if(begin instanceof RequestIdempotencyStore.BeginResult.Replay replay)
            return new CommandResult<>(replay.response().status(),idempotency.decode(replay.response(),Reserved.class));
        if(!properties.enabled()) throw new AnalysisConflictException("AI_DISABLED","AI analysis is disabled");
        if(state.version()!=command.expectedCaseVersion() || !java.util.Set.of("SUBMITTED","REVIEW_PENDING").contains(state.status()))
            throw new AnalysisConflictException("AI_INPUT_CONFLICT","Current reviewable case version is required");
        var source=store.source(caseId).orElseThrow(()->new AnalysisConflictException("AI_INPUT_CONFLICT","Successful parser and latest match are required"));
        var context=contexts.create(caseId,source);
        String canonical=AnalysisCanonicalJson.canonicalize(context),hash=AnalysisCanonicalJson.sha256Hex(canonical);
        var existing=store.existing(source.parserRunId(),source.matchId());
        Reserved response;
        if(existing.isPresent()) {
            var run=store.read(existing.get()).orElseThrow();
            if(!run.contextHash().equals(hash)) throw new AnalysisConflictException("AI_INPUT_CONFLICT","Frozen context changed");
            response=new Reserved(run.id(),run.status(),hash);
        } else {
            UUID id=UUID.randomUUID();
            var event=mapper.createObjectNode().put("schemaVersion","ai-request-v1").put("eventId",id.toString())
                    .put("proposalRunId",id.toString()).put("contextHash",hash).put("workflowVersion",ProposalRun.WORKFLOW);
            store.insert(id,caseId,source,canonical,hash,AnalysisCanonicalJson.canonicalize(event));
            response=new Reserved(id,"QUEUED",hash);
            audit.record(new AuditEvent(caseId,actor,AuditAction.AI_ANALYSIS_RESERVED,AuditTargetType.CASE,caseId.toString(),
                    state.version(),null,Map.of("proposalRunId",id,"contextHash",hash,"matchResultId",source.matchId()),
                    command.requestId(),clock.instant()));
        }
        idempotency.recordResponse(scope,resource,actor.username(),command.requestId(),201,response);
        return CommandResult.created(response);
    }
    @Transactional(readOnly=true)
    public ProposalStore.Saved read(UUID id) {
        var run=store.read(id).orElseThrow(()->new AnalysisRunNotFoundException(id));
        authorization.requireCaseRead(run.caseId());
        return store.saved(id).orElseThrow(()->new AnalysisConflictException("PROPOSAL_PENDING","Proposal is not complete"));
    }
}
