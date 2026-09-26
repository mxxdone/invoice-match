package com.invoicematch.core.matching.application;

import java.util.UUID;

/**
 * Internal test seam invoked after matching has taken the invoice case row
 * {@code PESSIMISTIC_WRITE} lock and before it reads the latest evidence
 * bundle. It lets a test hold a real match transaction mid-flight and race a
 * concurrent state-changing writer against it. Production uses {@link #NONE};
 * the type is package-private so it is not part of the feature's public surface.
 */
@FunctionalInterface
interface MatchLockInterceptor {

    MatchLockInterceptor NONE = caseId -> { };

    void afterCaseLocked(UUID caseId);
}
