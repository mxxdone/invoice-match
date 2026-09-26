package com.invoicematch.core.audit.application;

import java.util.List;

/** One page of audit history plus the cursor for the next page, if any. */
public record AuditHistoryPage(List<AuditEntryView> entries, String nextCursor) {
}
