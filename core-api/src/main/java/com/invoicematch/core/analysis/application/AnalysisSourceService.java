package com.invoicematch.core.analysis.application;

import com.invoicematch.core.document.application.DocumentOriginalRequest;
import com.invoicematch.core.document.application.DocumentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Storage I/O happens between two short authorization transactions. */
@Service
public class AnalysisSourceService {
    private final AnalysisExecutionService execution;
    private final DocumentStorage storage;

    public AnalysisSourceService(AnalysisExecutionService execution, DocumentStorage storage) {
        this.execution = execution;
        this.storage = storage;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Source read(UUID runId, UUID documentId, AnalysisHeartbeatCommand command) {
        DocumentOriginalRequest request = execution.authorizeSource(runId, documentId, command);
        byte[] bytes = storage.readOriginal(request);
        execution.authorizeSource(runId, documentId, command);
        return new Source(bytes, request.mediaType());
    }

    public record Source(byte[] bytes, String mediaType) { }
}
