package com.invoicematch.core.purchasingreference.domain;

/**
 * Status of a purchase order as reported by the external purchasing system.
 * Only {@link #CONFIRMED} purchase orders may be pulled into the local
 * reference snapshot; an {@link #UNCONFIRMED} order is an external fact that is
 * not yet final.
 */
public enum PurchaseOrderStatus {
    CONFIRMED,
    UNCONFIRMED
}
