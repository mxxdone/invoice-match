package com.invoicematch.core.review.application;

import java.util.UUID;

/** Exact advisory proof, selected by the human and rebuilt inside the locked review transaction. */
public interface ProposalEvidenceReader {
    record Reference(UUID id,String payloadHash,String contextHash) {}
    Reference verify(UUID caseId,UUID bundleId,UUID matchId,UUID proposalId,String payloadHash);
}
