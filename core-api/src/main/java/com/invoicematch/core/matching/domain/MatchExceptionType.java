package com.invoicematch.core.matching.domain;

/**
 * Machine-readable business exception taxonomy produced by the deterministic
 * 3-way match. A result may carry several of these at once, both across lines
 * and on a single line.
 */
public enum MatchExceptionType {

    /** The invoice line has no confirmed item, so no purchase order line can be chosen. */
    ITEM_UNCONFIRMED,

    /**
     * The confirmed item maps to zero or several active purchase order lines, so
     * the price and receipt basis cannot be uniquely supported.
     */
    EVIDENCE_INSUFFICIENT,

    /** The invoice quantity is above the confirmed receipt quantity available for planning. */
    QUANTITY_EXCEEDS_RECEIPT_BALANCE,

    /** The invoice unit price differs from the matched purchase order line unit price. */
    UNIT_PRICE_MISMATCH,

    /**
     * Another case with the same supplier and normalized invoice number exists.
     * This is a business duplicate and never rejects storage.
     */
    DUPLICATE_INVOICE_SUSPECTED
}
