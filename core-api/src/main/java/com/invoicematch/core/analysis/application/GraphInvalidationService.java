package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.persistence.GraphDeliveryStore;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Joins the authorized business write after its case lock; never touches another case. */
@Service
public class GraphInvalidationService {
    private final GraphStore graphs;
    private final GraphDeliveryStore deliveries;
    private final PolicyCatalogStore policies;
    private final ObjectMapper mapper;
    public GraphInvalidationService(GraphStore graphs, GraphDeliveryStore deliveries,PolicyCatalogStore policies,ObjectMapper mapper) {
        this.graphs = graphs;
        this.deliveries = deliveries;
        this.policies=policies;this.mapper=mapper;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateCase(UUID caseId) {
        for (UUID id : graphs.lockCaseRuns(caseId)) {
            graphs.terminal(id, "STALE", null);
            deliveries.cancel(id);
        }
    }
    /** The caller holds this case and its policy scope write lock; never acquire purchase locks here. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidatePolicyCase(UUID caseId) {
        for(UUID id:graphs.lockCaseRuns(caseId)) {
            if(!graphs.supported(id))continue;
            var run=graphs.lock(id).orElseThrow();
            try {
                var context=mapper.readTree(run.context());var match=context.path("matchResult");
                var current=mapper.valueToTree(policies.scope(context.path("companyId").asText(),match.path("supplierId").asText(),
                    match.path("purchaseOrderId").asText(),java.time.LocalDate.parse(context.path("applicableDate").asText())));
                if(!context.path("policyDocuments").equals(current)) {graphs.terminal(id,"STALE",null);deliveries.cancel(id);}
            } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Invalid stored graph context",e);}
        }
    }
}
