package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProposalQueryService {
    final ProposalStore store;final ProposalProperties properties;final ProposalCurrentness currentness;
    final ProposalSourceCatalog catalog;final AuthorizationService authorization;
    public ProposalQueryService(ProposalStore store,ProposalProperties properties,ProposalCurrentness currentness,ProposalSourceCatalog catalog,AuthorizationService authorization) {
        this.store=store;this.properties=properties;this.currentness=currentness;this.catalog=catalog;this.authorization=authorization;
    }
    public record Summary(UUID id,String status,boolean current,String contextHash,String payloadHash,int attempt,int reservedCalls,int reservedTokens,int toolCalls,String errorCode,List<String> completedStages) {}
    public record View(Summary run,JsonNode payload,List<ProposalSourceCatalog.Segment> sources) {}
    public record Page(boolean enabled,View latest,List<Summary> history) {}
    private void readPermission(UUID caseId) {authorization.requireRole(Role.OPERATOR,Role.APPROVER);authorization.requireCaseRead(caseId);}
    @Transactional(readOnly=true)
    public Page list(UUID caseId) {
        readPermission(caseId);var ids=store.recent(caseId);var history=ids.stream().map(this::summary).toList();
        return new Page(properties.enabled(),ids.isEmpty()?null:view(caseId,ids.getFirst()),history);
    }
    @Transactional(readOnly=true)
    public View view(UUID caseId,UUID id) {
        readPermission(caseId);var run=store.read(id).filter(r->r.caseId().equals(caseId)).orElseThrow(()->new AnalysisRunNotFoundException(id));
        var steps=store.steps(id);var saved=store.saved(id);
        return new View(summary(id),saved.map(s->catalog.parse(s.payload())).orElse(null),saved.isEmpty()?List.of():List.copyOf(catalog.sources(run,steps).values()));
    }
    private Summary summary(UUID id) {
        var run=store.read(id).orElseThrow();var saved=store.saved(id);
        return new Summary(id,run.status(),run.status().equals("COMPLETED")&&currentness.current(run),run.contextHash(),saved.map(ProposalStore.Saved::payloadHash).orElse(null),
            run.executionAttempt(),run.reservedCalls(),run.reservedTokens(),run.toolCalls(),run.errorCode(),store.steps(id).stream().map(ProposalStore.Step::stage).filter(s->!s.startsWith("tool:")).toList());
    }
}
