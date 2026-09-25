package com.invoicematch.core.purchasingreference.domain;

/**
 * Status of a receipt as reported by the external purchasing system. Only
 * {@link #CONFIRMED} receipts contribute confirmed quantity.
 */
public enum ReceiptStatus {
    CONFIRMED,
    UNCONFIRMED
}
