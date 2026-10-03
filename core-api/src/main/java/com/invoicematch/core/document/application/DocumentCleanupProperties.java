package com.invoicematch.core.document.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="document.cleanup")
public record DocumentCleanupProperties(boolean enabled,Duration grace,Duration leaseDuration,int batchSize,Duration retryDelay) {
    public DocumentCleanupProperties {
        grace=grace==null ? Duration.ofHours(24) : grace;
        leaseDuration=leaseDuration==null ? Duration.ofSeconds(60) : leaseDuration;
        retryDelay=retryDelay==null ? Duration.ofMinutes(5) : retryDelay;
        batchSize=batchSize==0 ? 10 : batchSize;
        if(grace.compareTo(Duration.ofHours(1))<0 || grace.compareTo(Duration.ofDays(30))>0)
            throw new IllegalArgumentException("Cleanup grace must be between one hour and 30 days");
        if(leaseDuration.compareTo(Duration.ofSeconds(20))<=0 || leaseDuration.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("Cleanup lease must exceed the storage deadline and be at most 10 minutes");
        if(batchSize<1 || batchSize>10 || retryDelay.compareTo(Duration.ofSeconds(30))<0 || retryDelay.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("Cleanup batch or retry delay out of bounds");
    }
}
