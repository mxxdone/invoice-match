package com.invoicematch.core.purchasingreference.domain;

/**
 * Typed outcome of applying one external purchase order aggregate snapshot to
 * the local current snapshot.
 */
public enum RefreshOutcome {
    /** No local snapshot existed; the fetched aggregate was stored. */
    CREATED,
    /** The fetched snapshot version was newer and replaced the stored data. */
    UPDATED,
    /** Same version and identical canonical payload; nothing changed. */
    UNCHANGED,
    /** The fetched version was older than the stored one; ignored. */
    STALE_IGNORED
}
