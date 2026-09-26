package com.invoicematch.core.purchasingreference.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The single transaction-scoped advisory lock key for a purchase order snapshot.
 *
 * <p>Both the snapshot refresh and every review write that validates against
 * the current purchasing snapshot acquire this same lock. That serializes a
 * refresh against a review decision from the final currentness validation to
 * commit, so a review snapshot can never be frozen from a purchasing snapshot
 * that changes before the review transaction commits.
 *
 * <p>It is a PostgreSQL transaction-level advisory lock: it is released
 * automatically at commit or rollback, and it is never held across the external
 * HTTP fetch (which runs before the applying transaction starts).
 */
@Component
public class PurchaseOrderSnapshotLock {

    /** Advisory-lock class shared by all purchase order snapshot writers/readers. */
    public static final int LOCK_CLASS = 1;

    private final JdbcTemplate jdbc;

    public PurchaseOrderSnapshotLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acquireXactLock(String purchaseOrderId) {
        jdbc.queryForList("select pg_advisory_xact_lock(?, hashtext(?))", LOCK_CLASS, purchaseOrderId);
    }
}
