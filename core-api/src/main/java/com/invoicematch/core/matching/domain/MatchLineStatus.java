package com.invoicematch.core.matching.domain;

/**
 * How one invoice line resolved against the purchase order and receipt facts.
 */
public enum MatchLineStatus {

    /** The item mapped to exactly one active purchase order line and was compared. */
    MATCHED,

    /** The line has no confirmed item. */
    ITEM_UNCONFIRMED,

    /** The confirmed item did not map to exactly one active purchase order line. */
    EVIDENCE_INSUFFICIENT
}
