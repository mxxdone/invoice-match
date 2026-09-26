package com.invoicematch.core.matching.application;

/**
 * One effective case-local item mapping applied to a frozen evidence line
 * during a deterministic match. {@code itemId} is the internal item the human
 * chose; the engine resolves it to an active purchase order line exactly as it
 * does for a line that carried a confirmed item at submission time.
 */
public record AppliedMapping(int lineNumber, String itemId) {

    public AppliedMapping {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive: " + lineNumber);
        }
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
    }
}
