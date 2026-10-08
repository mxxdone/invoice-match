package com.invoicematch.core.analysis.application;

import static com.invoicematch.core.analysis.application.GraphExecutionService.conflict;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotLock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared graph ownership/currentness rules; callers retain their business transactions. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class GraphExecutionGuard {
    private final GraphStore store;
    private final GraphProperties properties;
    private final PolicyCatalogStore policies;
    private final PurchaseOrderSnapshotLock purchaseLock;
    private final ObjectMapper mapper;

    GraphExecutionGuard(GraphStore store, GraphProperties properties, PolicyCatalogStore policies,
            PurchaseOrderSnapshotLock purchaseLock, ObjectMapper mapper) {
        this.store = store;
        this.properties = properties;
        this.policies = policies;
        this.purchaseLock = purchaseLock;
        this.mapper = mapper;
    }

    GraphRun lock(UUID id,String hash) {
        enabled();var run=store.lock(id).orElseThrow(()->new AnalysisRunNotFoundException(id));
        if(!store.supported(id))throw conflict("GRAPH_VERSION_UNSUPPORTED");
        if(!GraphPayloadValidator.hash(hash) || !run.contextHash().equals(hash))throw conflict("GRAPH_INPUT_MISMATCH");return run;
    }
    void enabled() {if(!properties.enabled())throw conflict("GRAPH_DISABLED");}
    void active(GraphRun run,UUID token) {
        if(run.status().equals("STALE"))throw conflict("STALE_INPUT");
        if(!run.status().equals("RUNNING") || token==null || !token.equals(run.token()) || !run.leaseActive())throw conflict("LEASE_CONFLICT");
        if(!current(run))throw conflict("STALE_INPUT");
        // Scope locks can block past lease expiry. Recheck database time after acquiring them.
        if(!store.owned(run.id(),token))throw conflict("LEASE_CONFLICT");
    }
    boolean current(GraphRun run) {
        var context=parse(run.context());var match=context.path("matchResult");
        purchaseLock.acquireXactLock(match.path("purchaseOrderId").asText());
        policies.lockScopeRead(match.path("purchaseOrderId").asText());
        return store.current(run) && context.path("policyDocuments").equals(mapper.valueToTree(policies.scope(context.path("companyId").asText(),
                match.path("supplierId").asText(),match.path("purchaseOrderId").asText(),LocalDate.parse(context.path("applicableDate").asText()))));
    }
    GraphRun reviewRun(UUID caseId,UUID id) {
        var run=store.lock(id).filter(r->r.caseId().equals(caseId)).orElseThrow(()->new AnalysisRunNotFoundException(id));
        if(!store.supported(id))throw conflict("GRAPH_VERSION_UNSUPPORTED");return run;
    }
    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored graph JSON");
        }
    }
}
