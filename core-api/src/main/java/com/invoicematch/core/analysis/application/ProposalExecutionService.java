package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.domain.ProposalRun;
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
    public ProposalExecutionService(ProposalStore store,ProposalProperties properties) { this.store=store;this.properties=properties; }
    public record Claim(String disposition, UUID token, Instant leaseUntil, String context, List<ProposalStore.Step> steps) {}
    @Transactional
    public Claim claim(UUID id, String contextHash) {
        var run=lock(id,contextHash);
        if(!store.current(run)) { store.stale(id);return new Claim("STALE",null,null,null,List.of()); }
        if(java.util.Set.of("COMPLETED","FAILED","STALE").contains(run.status()))
            return new Claim("ALREADY_FINISHED",null,null,null,List.of());
        if((run.status().equals("RUNNING") && run.leaseActive()) || !run.due())
            return new Claim("BUSY",null,run.leaseUntil(),null,List.of());
        UUID token=UUID.randomUUID();
        var until=store.claim(id,token,properties.leaseDuration()).orElseThrow(()->conflict("AI_ATTEMPTS_EXHAUSTED"));
        return new Claim("CLAIMED",token,until,run.context(),store.steps(id));
    }
    @Transactional
    public Instant heartbeat(UUID id,String hash,UUID token) {
        active(id,hash,token);
        return store.heartbeat(id,token,properties.leaseDuration()).orElseThrow(()->conflict("LEASE_CONFLICT"));
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
        store.reserveCall(id,requestId,tokens);
        return true;
    }
    @Transactional
    public ProposalRun active(UUID id,String hash,UUID token) {
        var run=lock(id,hash);
        if(!store.current(run)) throw conflict("STALE_INPUT");
        if(!run.status().equals("RUNNING") || !run.leaseActive() || token==null || !token.equals(run.executionToken()))
            throw conflict("LEASE_CONFLICT");
        return run;
    }
    // Per-stage semantic validators are added with their owning agent ticket.
    // No HTTP checkpoint/final-result endpoint exists before those validators.
    private ProposalRun lock(UUID id,String hash) {
        if(!properties.enabled()) throw conflict("AI_DISABLED");
        var run=store.lock(id).orElseThrow(()->new AnalysisRunNotFoundException(id));
        if(hash==null || !run.contextHash().equals(hash)) throw conflict("INPUT_MISMATCH");
        return run;
    }
    static AnalysisConflictException conflict(String code) { return new AnalysisConflictException(code,"Advisory execution conflicts with the current input or claim"); }
    private static AnalysisValidationException invalid() { return new AnalysisValidationException("VALIDATION_ERROR","Invalid call reservation"); }
}
