package com.invoicematch.core.analysis.application;

import java.util.Objects;
import java.util.UUID;

/**
 * SDK-free publish request handed to the {@link AnalysisRequestPublisher} port.
 * It carries exactly the event id (also the message id) and the immutable
 * canonical payload that was reserved at submission time. No credential, object
 * key or URL is included.
 */
public record AnalysisPublishCommand(UUID eventId, String payload, boolean deadLetter) {

    public AnalysisPublishCommand(UUID eventId, String payload) { this(eventId,payload,false); }

    public AnalysisPublishCommand {
        Objects.requireNonNull(eventId, "eventId");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("analysis publish payload must not be blank");
        }
    }
}
