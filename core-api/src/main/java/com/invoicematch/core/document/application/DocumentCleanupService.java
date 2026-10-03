package com.invoicematch.core.document.application;

import com.invoicematch.core.document.persistence.DocumentCleanupStore;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(DocumentCleanupProperties.class)
public class DocumentCleanupService {
    private final DocumentCleanupProperties properties;
    private final DocumentCleanupStore store;
    private final DocumentStorage storage;
    public DocumentCleanupService(DocumentCleanupProperties properties,DocumentCleanupStore store,DocumentStorage storage) {
        this.properties=properties;this.store=store;this.storage=storage;
    }
    @Transactional(propagation=Propagation.NEVER)
    public int runOnce() {
        if(!properties.enabled()) return 0;
        int processed=0;
        for(int i=0;i<properties.batchSize();i++) {
            var candidate=store.claim(properties.grace(),properties.leaseDuration());
            if(candidate.isEmpty()) break;
            var claim=candidate.get();
            if(!claim.uploadKey().equals("uploads/"+claim.caseId()+"/"+claim.uploadId())) {
                store.settle(claim,"BLOCKED","INVALID_UPLOAD_KEY",Duration.ZERO);processed++;continue;
            }
            try {
                storage.removeTemporary(claim.caseId(),claim.uploadId());
                store.settle(claim,"DONE",null,Duration.ZERO);
            } catch(DocumentFailure e) {
                store.settle(claim,"READY","DOCUMENT_STORAGE_UNAVAILABLE",properties.retryDelay());
            }
            processed++;
        }
        return processed;
    }
}
