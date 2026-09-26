package com.invoicematch.core.matching.application;

/**
 * One effective case-local item mapping applied to a frozen evidence line
 * during a deterministic match.
 *
 * <p>It keeps both the {@code itemId} the human chose and the exact
 * {@code purchaseOrderLineId} it resolved to at decision time. Re-matching uses
 * that exact active purchase order line rather than re-resolving the item, so a
 * later purchasing refresh that moves the item to a different line can never
 * silently retarget the mapping; if the chosen line is no longer active the
 * line is reported as insufficient evidence and needs a new mapping decision.
 */
public record AppliedMapping(int lineNumber, String itemId, String purchaseOrderLineId) {

    public AppliedMapping {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive: " + lineNumber);
        }
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (purchaseOrderLineId == null || purchaseOrderLineId.isBlank()) {
            throw new IllegalArgumentException("purchaseOrderLineId must not be blank");
        }
    }
}
