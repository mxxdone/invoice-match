package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.GraphRun;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.invoicematch.core.review.application.ProposalEvidenceReader;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reconstructs a stored graph completion even when the graph runtime is disabled. */
@Service
public class GraphProposalProofService {
    private final GraphStore store;
    private final GraphExecutionGuard guard;
    private final GraphProposalAssembler assembler;
    private final GraphReviewValidator validator;
    private final ProposalSourceCatalog sources;
    private final PolicyCatalogStore policies;
    private final ObjectMapper mapper;
    public GraphProposalProofService(GraphStore store,GraphExecutionGuard guard,GraphProposalAssembler assembler,
            GraphReviewValidator validator,ProposalSourceCatalog sources,PolicyCatalogStore policies,ObjectMapper mapper) {
        this.store=store;this.guard=guard;this.assembler=assembler;this.validator=validator;
        this.sources=sources;this.policies=policies;this.mapper=mapper;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ProposalEvidenceReader.Reference verify(UUID caseId,UUID bundleId,UUID matchId,UUID graphId,String hash) {
        if(graphId==null || hash==null || !hash.matches("[0-9a-f]{64}")) throw conflict(caseId);
        var run=store.lock(graphId).orElseThrow(()->conflict(caseId));
        if(!run.caseId().equals(caseId) || !run.evidenceBundleId().equals(bundleId) || !run.matchResultId().equals(matchId)
            || run.checkpointSchema()!=GraphRun.SCHEMA || !run.status().equals("COMPLETED")) throw conflict(caseId);
        var input=store.advisoryInput(graphId);
        if(!AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(parse(input.context()))).equals(run.contextHash())) throw conflict(caseId);
        if(!guard.current(run)) throw conflict(caseId);
        var result=store.result(graphId).orElseThrow(()->conflict(caseId));
        if(!result.hash().equals(hash)) throw conflict(caseId);
        var stored=parse(result.payload());
        if(!stored.path("schemaVersion").asText().equals("advisory-proposal-v2")
            || !stored.path("graphVersion").asText().equals(GraphRun.GRAPH)
            || !AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(stored)).equals(hash)) throw conflict(caseId);
        try {
            var steps=store.validationSteps(graphId);
            var assembled=assembler.assemble(run,input,steps);
            if(!assembled.hash().equals(hash)) throw conflict(caseId);
            if(run.segment().equals("RESUME")) verifyHumanReview(run);
            verifyPolicyEvidence(caseId,input,stored);
        } catch(AnalysisValidationException|AnalysisConflictException|IllegalArgumentException|IllegalStateException e) {
            throw conflict(caseId);
        }
        return new ProposalEvidenceReader.Reference(graphId,hash,run.contextHash());
    }
    private void verifyHumanReview(GraphRun run) {
        var review=store.review(run.id()).orElseThrow(()->conflict(run.caseId()));
        var wait=store.waiting(run.id()).orElseThrow(()->conflict(run.caseId()));
        var confirmation=parse(review.confirmation());
        if(!review.hash().equals(AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(confirmation)))) throw conflict(run.caseId());
        if(!store.reviewConsumed(run.id(),review.id())) throw conflict(run.caseId());
        validator.validate(run,wait,confirmation,store);
    }
    private void verifyPolicyEvidence(UUID caseId,ProposalRun input,JsonNode payload) {
        var documents=new HashMap<String,JsonNode>();
        for(var document:sources.context(input).path("policyDocuments")) documents.put(document.path("id").asText(),document);
        for(var hit:payload.path("policyEvidence").path("result")) {
            var document=documents.get(hit.path("documentId").asText());
            var chunk=policies.chunk(UUID.fromString(hit.path("chunkId").asText())).orElseThrow(()->conflict(caseId));
            if(document==null || !chunk.documentId().toString().equals(hit.path("documentId").asText())
                || chunk.page()!=hit.path("page").asInt() || chunk.paragraph()!=hit.path("paragraph").asInt()
                || !chunk.content().equals(hit.path("text").asText()) || !chunk.payloadHash().equals(hit.path("chunkHash").asText())
                || document.path("version").asInt()!=hit.path("documentVersion").asInt()
                || !document.path("payloadHash").equals(hit.path("documentHash"))
                || !document.path("contractId").equals(hit.path("contractId"))
                || !document.path("title").equals(hit.path("title"))) throw conflict(caseId);
        }
    }
    private JsonNode parse(String value) {
        try {return mapper.readTree(value);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Invalid stored graph JSON");}
    }
    private static ReviewStateConflictException conflict(UUID caseId) {
        return new ReviewStateConflictException(caseId,"The selected graph proposal is not an intact, current proof for this review");
    }
}
