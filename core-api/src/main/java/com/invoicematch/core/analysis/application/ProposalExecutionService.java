package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.domain.ProposalRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fenced advisory execution. No provider, storage or broker calls inside transactions. */
@Service
public class ProposalExecutionService {
    private final ProposalStore store;
    private final ProposalProperties properties;
    private final ProposalStageValidator stages;
    private final ProposalCurrentness currentness;
    private final ProposalSourceCatalog catalog;
    private final ProposalAssembler assembler;
    private final com.invoicematch.core.analysis.persistence.ProposalRecoveryStore recovery;
    private final com.invoicematch.core.document.persistence.DocumentStore documents;
    public ProposalExecutionService(ProposalStore store,ProposalProperties properties,ProposalStageValidator stages,ProposalCurrentness currentness,ProposalSourceCatalog catalog,ProposalAssembler assembler,com.invoicematch.core.analysis.persistence.ProposalRecoveryStore recovery,com.invoicematch.core.document.persistence.DocumentStore documents) {
        this.store=store;this.properties=properties;this.stages=stages;this.currentness=currentness;this.catalog=catalog;this.assembler=assembler;this.recovery=recovery;this.documents=documents;
    }
    public record Claim(String disposition, UUID token, Instant leaseUntil, String context, List<ProposalStore.Step> steps) {}
    @Transactional
    public Claim claim(UUID id, String contextHash) {
        var run=lock(id,contextHash);
        if(!currentness.currentLocked(run)) { store.stale(id);return new Claim("STALE",null,null,null,List.of()); }
        if(java.util.Set.of("COMPLETED","FAILED","STALE").contains(run.status()))
            return new Claim("ALREADY_FINISHED",null,null,null,List.of());
        if((run.status().equals("RUNNING") && run.leaseActive()) || !run.due())
            return new Claim("BUSY",null,run.leaseUntil(),null,List.of());
        if(run.executionAttempt()>=3) {recovery.exhausted(id);return new Claim("ALREADY_FINISHED",null,null,null,List.of());}
        UUID token=UUID.randomUUID();
        var until=store.claim(id,token,properties.leaseDuration()).orElseThrow(()->conflict("AI_ATTEMPTS_EXHAUSTED"));
        var savedSteps=store.steps(id);
        int wireBytes=run.context().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for(var step:savedSteps) wireBytes+=step.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(wireBytes>1500000) throw new AnalysisValidationException("AI_INPUT_LIMIT","Advisory claim exceeds the limit");
        return new Claim("CLAIMED",token,until,run.context(),savedSteps);
    }
    public record Checkpoint(String disposition,String runStatus) {}
    @Transactional
    public Checkpoint defer(UUID id,String hash) {
        var run=lock(id,hash);
        if(!currentness.currentLocked(run)) {store.stale(id);return new Checkpoint("CHECKPOINTED","STALE");}
        if(java.util.Set.of("COMPLETED","FAILED","STALE").contains(run.status())) return new Checkpoint("CHECKPOINTED",run.status());
        recovery.dispatch(id,run.status().equals("RUNNING")?"defer:"+run.executionToken():"queued:"+run.executionAttempt(),java.time.Duration.ofSeconds(1),true);
        return new Checkpoint("CHECKPOINTED",run.status());
    }
    @Transactional
    public Checkpoint failure(UUID id,String hash,UUID token,String code) {
        var run=lock(id,hash);
        var retryable=java.util.Set.of("AI_RATE_LIMIT","AI_TIMEOUT","AI_FAILED","OCR_RATE_LIMIT","OCR_TIMEOUT","OCR_FAILED","CORE_UNAVAILABLE","CORE_TRANSIENT");
        var permanent=java.util.Set.of("AI_CONFIGURATION","OCR_CONFIGURATION","OCR_LIMIT","OCR_INVALID_RESPONSE","SOURCE_MISMATCH","AI_SCHEMA_INVALID","AI_BUDGET_EXHAUSTED","AI_INPUT_LIMIT","INVALID_PROTOCOL","AI_TOOL_DENIED","WORKER_FAILED");
        if(token==null || code==null || !(retryable.contains(code)||permanent.contains(code))) throw invalid();
        if(!currentness.currentLocked(run)) {store.stale(id);return new Checkpoint("CHECKPOINTED","STALE");}
        if(java.util.Set.of("COMPLETED","FAILED","STALE").contains(run.status()) || recovery.recorded(id,token)) return new Checkpoint("CHECKPOINTED",run.status());
        active(id,hash,token);
        boolean retry=retryable.contains(code)&&run.executionAttempt()<3;
        recovery.failure(id,token,run.executionAttempt(),code,retry,java.time.Duration.ofSeconds(retry?5L<<(run.executionAttempt()-1):0));
        return new Checkpoint("CHECKPOINTED",retry?"QUEUED":"FAILED");
    }
    @Transactional
    public com.invoicematch.core.document.application.DocumentOriginalRequest authorizeSource(UUID id,String hash,UUID token,UUID documentId) {
        var run=active(id,hash,token);
        var context=catalog.context(run);
        var frozen=java.util.stream.StreamSupport.stream(context.path("evidenceBundle").path("documents").spliterator(),false)
            .filter(d->d.path("documentId").asText().equals(documentId.toString())).findFirst().orElseThrow(ProposalSourceCatalog::invalid);
        var registered=documents.document(run.caseId(),documentId).orElseThrow(ProposalSourceCatalog::invalid);var u=registered.upload();
        if(!u.draftRevisionId().toString().equals(frozen.path("sourceDraftRevisionId").asText()) || !u.fileName().equals(frozen.path("fileName").asText())
            || !u.mediaType().equals(frozen.path("mediaType").asText()) || u.sizeBytes()!=frozen.path("sizeBytes").asLong() || !u.checksum().equals(frozen.path("checksum").asText())) throw ProposalSourceCatalog.invalid();
        return new com.invoicematch.core.document.application.DocumentOriginalRequest(registered.objectKey(),u.mediaType(),u.sizeBytes(),u.checksum());
    }
    @Transactional
    public Instant heartbeat(UUID id,String hash,UUID token) {
        active(id,hash,token);
        return store.heartbeat(id,token,properties.leaseDuration()).orElseThrow(()->conflict("LEASE_CONFLICT"));
    }
    @Transactional
    public boolean reserveConfiguredCall(UUID id,String hash,UUID token,UUID requestId,int tokens) {
        var run=active(id,hash,token);
        var plan=ProposalResolutionValidator.step("execution",store.steps(id),catalog);
        stages.validate("execution",plan,run,store.steps(id));
        return reserveCall(id,hash,token,requestId,tokens);
    }
    @Transactional
    public boolean reserveCall(UUID id,String hash,UUID token,UUID requestId,int tokens) {
        var run=active(id,hash,token);
        if(requestId==null || tokens<1 || tokens>ProposalRun.MAX_TOKENS) throw invalid();
        var existing=store.reservation(id,requestId);
        // A replay proves budget was already spent; it is not permission to call
        // a provider again. An uncertain prior call needs a fresh reservation.
        if(existing.isPresent()) { if(existing.get()!=tokens) throw conflict("CALL_CONFLICT");return false; }
        if(run.reservedCalls()>=ProposalRun.MAX_CALLS || run.reservedTokens()+tokens>ProposalRun.MAX_TOKENS)
            throw conflict("AI_BUDGET_EXHAUSTED");
        var plan=store.steps(id).stream().filter(s->s.stage().equals("execution")).findFirst();
        if(plan.isPresent()) {
            var config=catalog.parse(plan.get().payload());
            var rate=config.path("inputPricePerMillion").decimalValue().max(config.path("outputPricePerMillion").decimalValue());
            rate=rate.max(config.path("embedding").path("pricePerMillion").decimalValue());
            var upper=rate.multiply(java.math.BigDecimal.valueOf(run.reservedTokens()+tokens)).divide(java.math.BigDecimal.valueOf(1000000));
            if(upper.compareTo(config.path("costCeiling").decimalValue())>0) throw conflict("AI_BUDGET_EXHAUSTED");
        }
        store.reserveCall(id,requestId,tokens);
        return true;
    }
    @Transactional
    public ProposalRun active(UUID id,String hash,UUID token) {
        var run=lock(id,hash);
        if(!currentness.currentLocked(run)) throw conflict("STALE_INPUT");
        if(!run.status().equals("RUNNING") || !run.leaseActive() || token==null || !token.equals(run.executionToken()))
            throw conflict("LEASE_CONFLICT");
        return run;
    }
    @Transactional
    public String checkpoint(UUID id,String hash,UUID token,String stage,JsonNode payload) {
        var run=lock(id,hash);
        if(!currentness.currentLocked(run)) { store.stale(id);return "STALE"; }
        var checkpoints=store.steps(id);
        stages.validate(stage,payload,run,checkpoints);
        String canonical=AnalysisCanonicalJson.canonicalize(payload);
        if(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>120000 || store.jsonStorageBytes(canonical)>131072)
            throw new AnalysisValidationException("AI_INPUT_LIMIT","Advisory checkpoint exceeds the limit");
        String payloadHash=AnalysisCanonicalJson.sha256Hex(canonical);
        var existing=checkpoints.stream().filter(s->s.stage().equals(stage)).findFirst();
        if(existing.isPresent()) {
            if(!existing.get().hash().equals(payloadHash)) throw conflict("STEP_CONFLICT");
            return "REPLAYED";
        }
        int knownCalls=payload.path("calls").size(),knownTokens=usage(payload);
        for(var step:checkpoints) if(!step.stage().startsWith("tool:")) {
            var saved=catalog.parse(step.payload());knownCalls+=saved.path("calls").size();knownTokens+=usage(saved);
        }
        if(knownCalls>run.reservedCalls() || knownTokens>run.reservedTokens()) throw ProposalSourceCatalog.invalid();
        active(id,hash,token);
        store.step(id,stage,canonical,payloadHash);
        return "ACCEPTED";
    }
    @Transactional
    public ProposalStore.Saved complete(UUID id,String hash,UUID token) {
        var run=lock(id,hash);
        if(!currentness.currentLocked(run)) throw conflict("STALE_INPUT");
        var existing=store.saved(id);
        if(existing.isPresent()) return existing.get();
        active(id,hash,token);
        var assembled=assembler.assemble(run,store.steps(id));
        if(store.jsonStorageBytes(assembled.canonical())>131072) throw new AnalysisValidationException("AI_INPUT_LIMIT","Advisory proposal exceeds the limit");
        store.save(run,assembled.canonical(),assembled.hash());
        recovery.cancelPending(id);
        return store.saved(id).orElseThrow();
    }
    private static int usage(JsonNode payload) {
        int tokens=0;for(var call:payload.path("calls")) tokens+=call.path("inputTokens").asInt()+call.path("outputTokens").asInt();return tokens;
    }
    private ProposalRun lock(UUID id,String hash) {
        if(!properties.enabled()) throw conflict("AI_DISABLED");
        var run=store.lock(id).orElseThrow(()->new AnalysisRunNotFoundException(id));
        if(hash==null || !run.contextHash().equals(hash)) throw conflict("INPUT_MISMATCH");
        return run;
    }
    static AnalysisConflictException conflict(String code) { return new AnalysisConflictException(code,"Advisory execution conflicts with the current input or claim"); }
    private static AnalysisValidationException invalid() { return new AnalysisValidationException("VALIDATION_ERROR","Invalid call reservation"); }
}
