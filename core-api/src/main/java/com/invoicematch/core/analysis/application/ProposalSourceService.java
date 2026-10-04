package com.invoicematch.core.analysis.application;

import com.invoicematch.core.document.application.DocumentStorage;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProposalSourceService {
    final ProposalExecutionService execution;final DocumentStorage storage;
    public ProposalSourceService(ProposalExecutionService execution,DocumentStorage storage) {this.execution=execution;this.storage=storage;}
    @Transactional(propagation=Propagation.NEVER)
    public AnalysisSourceService.Source read(UUID id,String hash,UUID token,UUID documentId) {
        var authorized=execution.authorizeSource(id,hash,token,documentId);
        byte[] bytes=storage.readOriginal(authorized);
        execution.authorizeSource(id,hash,token,documentId);
        return new AnalysisSourceService.Source(bytes,authorized.mediaType());
    }
}
