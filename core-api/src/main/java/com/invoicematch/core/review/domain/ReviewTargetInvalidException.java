package com.invoicematch.core.review.domain;

import java.util.UUID;

/**
 * A mapping decision named an invoice line or item that cannot be resolved
 * against the target snapshot/bundle and the current purchase order: the line
 * is not in the frozen bundle, or the chosen item matches zero or several
 * active purchase order lines. Reported as an HTTP 409 with no partial rows.
 */
public class ReviewTargetInvalidException extends RuntimeException {

    public ReviewTargetInvalidException(UUID caseId, String message) {
        super("Invoice case " + caseId + ": " + message);
    }
}
