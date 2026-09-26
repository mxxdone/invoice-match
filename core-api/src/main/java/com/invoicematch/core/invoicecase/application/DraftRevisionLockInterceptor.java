package com.invoicematch.core.invoicecase.application;

import java.util.UUID;

/**
 * Internal test seam invoked after submission has taken the draft revision row
 * lock and before it reads the invoice lines. It lets a test hold a real
 * submission mid-transaction and race a raw line mutation against the seal.
 * Production uses {@link #NONE}; the type is package-private so it is not part
 * of the feature's public surface.
 */
@FunctionalInterface
interface DraftRevisionLockInterceptor {

    DraftRevisionLockInterceptor NONE = draftRevisionId -> { };

    void afterLocked(UUID draftRevisionId);
}
