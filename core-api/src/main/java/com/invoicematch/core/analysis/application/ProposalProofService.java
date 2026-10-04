package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.persistence.ProposalStore;
import com.invoicematch.core.analysis.persistence.PolicyCatalogStore;
import com.invoicematch.core.review.application.ProposalEvidenceReader;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reconstructs the selected exact proposal even when external AI execution is disabled. */
@Service
public class ProposalProofService implements ProposalEvidenceReader {
    final ProposalStore store;final ProposalCurrentness currentness;final ProposalAssembler assembler;
    final ProposalSourceCatalog sources;final PolicyCatalogStore policies;
    public ProposalProofService(ProposalStore store,ProposalCurrentness currentness,ProposalAssembler assembler,ProposalSourceCatalog sources,PolicyCatalogStore policies) {
        this.store=store;this.currentness=currentness;this.assembler=assembler;this.sources=sources;this.policies=policies;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Reference verify(UUID caseId,UUID bundleId,UUID matchId,UUID proposalId,String hash) {
        if(proposalId==null || hash==null || !hash.matches("[0-9a-f]{64}")) throw conflict(caseId);
        var header=store.read(proposalId).orElseThrow(()->conflict(caseId));
        if(!header.caseId().equals(caseId) || !header.bundleId().equals(bundleId) || !header.matchResultId().equals(matchId)) throw conflict(caseId);
        var run=store.lock(proposalId).orElseThrow(()->conflict(caseId));
        var saved=store.saved(proposalId).orElseThrow(()->conflict(caseId));
        if(!run.caseId().equals(caseId) || !run.bundleId().equals(bundleId) || !run.matchResultId().equals(matchId)
            || !run.status().equals("COMPLETED") || !saved.caseId().equals(caseId) || !saved.bundleId().equals(bundleId)
            || !saved.matchId().equals(matchId) || !saved.contextHash().equals(run.contextHash()) || !saved.payloadHash().equals(hash)
            || !currentness.currentLocked(run)) throw conflict(caseId);
        try {
            var assembled=assembler.assemble(run,store.steps(proposalId));
            if(!assembled.hash().equals(hash) || !AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(sources.parse(saved.payload()))).equals(hash)) throw conflict(caseId);
            var payload=sources.parse(assembled.canonical());var evidence=payload.path("policyEvidence");
            var documents=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
            for(var d:sources.context(run).path("policyDocuments")) documents.put(d.path("id").asText(),d);
            for(var hit:evidence.path("result")) {
                var d=documents.get(hit.path("documentId").asText());
                var chunk=policies.chunk(UUID.fromString(hit.path("chunkId").asText())).orElseThrow(()->conflict(caseId));
                if(d==null || !chunk.documentId().toString().equals(hit.path("documentId").asText()) || chunk.page()!=hit.path("page").asInt()
                    || chunk.paragraph()!=hit.path("paragraph").asInt() || !chunk.content().equals(hit.path("text").asText())
                    || !chunk.payloadHash().equals(hit.path("chunkHash").asText()) || d.path("version").asInt()!=hit.path("documentVersion").asInt()
                    || !d.path("payloadHash").equals(hit.path("documentHash")) || !d.path("contractId").equals(hit.path("contractId"))
                    || !d.path("title").equals(hit.path("title"))) throw conflict(caseId);
            }
            return new Reference(proposalId,hash,run.contextHash());
        } catch(AnalysisValidationException|IllegalArgumentException e) {throw conflict(caseId);}
    }
    private static ReviewStateConflictException conflict(UUID caseId) {return new ReviewStateConflictException(caseId,"The selected advisory proposal is not an intact, current proof for this review");}
}
