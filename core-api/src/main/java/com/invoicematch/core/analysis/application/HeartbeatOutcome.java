package com.invoicematch.core.analysis.application;

import java.time.Instant;

/** The extended lease deadline after a heartbeat. */
public record HeartbeatOutcome(Instant leaseUntil) {
}
