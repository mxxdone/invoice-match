package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.persistence.GraphStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import com.invoicematch.core.review.application.ProposalEvidenceReader;
import com.invoicematch.core.review.domain.ReviewStateConflictException;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Dispatches by the selected id's real storage origin: v1 proposal_run or graph_run. */
@Primary
@Service
public class AdvisoryProposalProofRouter implements ProposalEvidenceReader {
    private final ProposalProofService proposals;
    private final GraphProposalProofService graphs;
    private final ProposalStore proposalStore;
    private final GraphStore graphStore;
    public AdvisoryProposalProofRouter(ProposalProofService proposals,GraphProposalProofService graphs,
            ProposalStore proposalStore,GraphStore graphStore) {
        this.proposals=proposals;this.graphs=graphs;this.proposalStore=proposalStore;this.graphStore=graphStore;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Reference verify(UUID caseId,UUID bundleId,UUID matchId,UUID proposalId,String payloadHash) {
        if(proposalId==null) throw conflict(caseId);
        if(proposalStore.read(proposalId).isPresent()) return proposals.verify(caseId,bundleId,matchId,proposalId,payloadHash);
        if(graphStore.read(proposalId).isPresent()) return graphs.verify(caseId,bundleId,matchId,proposalId,payloadHash);
        throw conflict(caseId);
    }
    private static ReviewStateConflictException conflict(UUID caseId) {
        return new ReviewStateConflictException(caseId,"The selected advisory proposal is not an intact, current proof for this review");
    }
}
