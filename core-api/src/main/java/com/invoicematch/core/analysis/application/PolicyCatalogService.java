package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized append-only ingestion. Caller cannot select company or supplier scope. */
@Service
public class PolicyCatalogService {
    private final PolicyCatalogStore policies;
    private final ProposalStore cases;
    private final AuthorizationService authorization;
    private final RequestIdempotencyStore idempotency;
    private final ObjectMapper mapper;
    private final com.invoicematch.core.audit.application.AuditRecorder audit;
    private final java.time.Clock clock;
    private final GraphInvalidationService graphs;
    public PolicyCatalogService(PolicyCatalogStore policies,ProposalStore cases,AuthorizationService authorization,
            RequestIdempotencyStore idempotency,ObjectMapper mapper,com.invoicematch.core.audit.application.AuditRecorder audit,java.time.Clock clock,GraphInvalidationService graphs) {
        this.policies=policies;this.cases=cases;this.authorization=authorization;this.idempotency=idempotency;this.mapper=mapper;this.audit=audit;this.clock=clock;
        this.graphs=graphs;
    }
    public record ChunkInput(int page,int paragraph,String content,float[] embedding,String ruleKey,String effect) {
        public ChunkInput(int page,int paragraph,String content,float[] embedding) { this(page,paragraph,content,embedding,null,"INFORMATION"); }
    }
    public record Publish(String requestId,long expectedCaseVersion,String contractId,String documentKey,int version,
            String title,LocalDate validFrom,LocalDate validTo,String embeddingModel,String embeddingVersion,List<ChunkInput> chunks) {}
    public record Published(UUID documentId,String payloadHash) {}
    @Transactional
    public CommandResult<Published> publish(UUID caseId,Publish input) {
        authorization.requireRole(Role.OPERATOR);
        if(input==null || !text(input.requestId(),80) || input.expectedCaseVersion()<0 || !text(input.contractId(),64)
                || !text(input.documentKey(),64) || input.version()<1 || !text(input.title(),200)
                || input.validFrom()==null || input.validTo()==null || input.validTo().isBefore(input.validFrom())
                || !text(input.embeddingVersion(),100) || input.chunks()==null || input.chunks().isEmpty() || input.chunks().size()>50) throw PolicySearchService.invalid("VALIDATION_ERROR");
        if(input.chunks().getFirst()==null) throw PolicySearchService.invalid("VALIDATION_ERROR");
        int dimension=input.chunks().getFirst().embedding()==null?0:input.chunks().getFirst().embedding().length;
        var positions=new HashSet<String>();
        for(var chunk:input.chunks()) {
            if(chunk==null || chunk.page()<1 || chunk.page()>1000 || chunk.paragraph()<1 || chunk.paragraph()>1000
                    || !text(chunk.content(),2000) || !positions.add(chunk.page()+":"+chunk.paragraph())) throw PolicySearchService.invalid("VALIDATION_ERROR");
            if(!java.util.Set.of("INFORMATION","ALLOW","DENY").contains(chunk.effect()==null?"":chunk.effect())
                    || (chunk.ruleKey()!=null && !chunk.ruleKey().matches("[A-Z0-9_]{1,64}"))
                    || (!"INFORMATION".equals(chunk.effect()) && chunk.ruleKey()==null)) throw PolicySearchService.invalid("VALIDATION_ERROR");
            PolicySearchService.validateEmbedding(input.embeddingModel(),chunk.embedding());
            if(chunk.embedding().length!=dimension) throw PolicySearchService.invalid("AI_EMBEDDING_MISMATCH");
        }
        var state=cases.lockCase(caseId).orElseThrow(()->new com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException(caseId));
        var scope=policies.caseScope(caseId).orElseThrow(()->PolicySearchService.invalid("INVALID_POLICY_SCOPE"));
        policies.lockScopeWrite(scope.purchaseOrderId());
        String canonical=AnalysisCanonicalJson.canonicalize(mapper.valueToTree(input));
        if(canonical.getBytes(StandardCharsets.UTF_8).length>262144) throw PolicySearchService.invalid("AI_INPUT_LIMIT");
        String fingerprint=AnalysisCanonicalJson.sha256Hex(canonical),actor=authorization.actor().username();
        var begin=idempotency.begin("policy:publish",caseId.toString(),actor,input.requestId(),fingerprint);
        if(begin instanceof RequestIdempotencyStore.BeginResult.Replay replay)
            return new CommandResult<>(replay.response().status(),idempotency.decode(replay.response(),Published.class));
        if(state.version()!=input.expectedCaseVersion()) throw ProposalExecutionService.conflict("STALE_INPUT");
        var artifact=mapper.valueToTree(input);((com.fasterxml.jackson.databind.node.ObjectNode)artifact).remove(List.of("requestId","expectedCaseVersion"));
        String hash=AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(artifact));UUID id=UUID.randomUUID();
        policies.contract("company-demo",scope.supplierId(),input.contractId(),scope.purchaseOrderId());
        policies.document(id,"company-demo",scope.supplierId(),input.contractId(),input.documentKey(),input.version(),input.title(),
                input.validFrom(),input.validTo(),hash,input.embeddingModel(),input.embeddingVersion(),dimension);
        for(var chunk:input.chunks()) policies.chunk(new PolicyCatalogStore.Chunk(UUID.randomUUID(),id,chunk.page(),chunk.paragraph(),
                chunk.content(),AnalysisCanonicalJson.sha256Hex(chunk.content()),chunk.ruleKey(),chunk.effect()),chunk.embedding());
        graphs.invalidatePolicyCase(caseId);
        audit.record(new com.invoicematch.core.audit.application.AuditEvent(caseId,authorization.actor(),
                com.invoicematch.core.audit.domain.AuditAction.POLICY_DOCUMENT_PUBLISHED,
                com.invoicematch.core.audit.domain.AuditTargetType.CASE,caseId.toString(),state.version(),null,
                java.util.Map.of("documentId",id,"payloadHash",hash,"version",input.version()),input.requestId(),clock.instant()));
        var published=new Published(id,hash);
        idempotency.recordResponse("policy:publish",caseId.toString(),actor,input.requestId(),201,published);
        return CommandResult.created(published);
    }
    private static boolean text(String value,int max) {
        return value!=null && !value.isBlank() && value.length()<=max && value.indexOf('\0')<0;
    }
}
