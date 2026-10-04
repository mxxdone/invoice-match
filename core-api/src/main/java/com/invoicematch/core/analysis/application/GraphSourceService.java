package com.invoicematch.core.analysis.application;

import com.invoicematch.core.document.application.DocumentStorage;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** External object I/O occurs between two short Core authorization transactions. */
@Service
public class GraphSourceService {
    private final GraphStageService stages;
    private final DocumentStorage storage;
    public GraphSourceService(GraphStageService stages,DocumentStorage storage) {this.stages=stages;this.storage=storage;}
    @Transactional(propagation=Propagation.NEVER)
    public AnalysisSourceService.Source read(UUID id,String hash,UUID token,UUID documentId) {
        var authorized=stages.authorizeSource(id,hash,token,documentId);
        byte[] bytes=storage.readOriginal(authorized);
        stages.authorizeSource(id,hash,token,documentId);
        return new AnalysisSourceService.Source(bytes,authorized.mediaType());
    }
}
