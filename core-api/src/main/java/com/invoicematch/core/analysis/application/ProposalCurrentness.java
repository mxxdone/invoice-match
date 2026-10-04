package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** A policy publication is new input just like a new match or purchasing snapshot. */
@Component
public class ProposalCurrentness {
    private final ProposalStore store;
    private final PolicyCatalogStore policies;
    private final ProposalSourceCatalog sources;
    private final ObjectMapper mapper;
    public ProposalCurrentness(ProposalStore store,PolicyCatalogStore policies,ProposalSourceCatalog sources,ObjectMapper mapper) {
        this.store=store;this.policies=policies;this.sources=sources;this.mapper=mapper;
    }
    public boolean currentLocked(ProposalRun run) {
        var context=sources.context(run);
        policies.lockScopeRead(context.path("matchResult").path("purchaseOrderId").asText());
        return current(run);
    }
    public boolean current(ProposalRun run) {
        if(!store.current(run)) return false;
        var context=sources.context(run);var match=context.path("matchResult");
        var documents=policies.scope(context.path("companyId").asText(),match.path("supplierId").asText(),
                match.path("purchaseOrderId").asText(),LocalDate.parse(context.path("applicableDate").asText()));
        // Contexts issued before the catalog existed had an empty policy scope.
        var frozen=context.has("policyDocuments")?context.path("policyDocuments"):mapper.createArrayNode();
        return frozen.equals(mapper.valueToTree(documents));
    }
}
