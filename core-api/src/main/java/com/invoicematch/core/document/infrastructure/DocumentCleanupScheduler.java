package com.invoicematch.core.document.infrastructure;

import com.invoicematch.core.document.application.DocumentCleanupService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="document.cleanup",name="enabled",havingValue="true")
public class DocumentCleanupScheduler {
    private final DocumentCleanupService service;
    private final AtomicBoolean running=new AtomicBoolean();
    public DocumentCleanupScheduler(DocumentCleanupService service) { this.service=service; }
    @Scheduled(fixedDelayString="${document.cleanup.interval:1h}",initialDelayString="${document.cleanup.initial-delay:1m}")
    public void tick() {
        if(!running.compareAndSet(false,true)) return;
        try { service.runOnce(); } finally { running.set(false); }
    }
}
