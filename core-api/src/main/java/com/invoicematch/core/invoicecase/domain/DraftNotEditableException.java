package com.invoicematch.core.invoicecase.domain;

import java.util.UUID;

/**
 * Thrown when a draft edit targets a case whose current revision is not OPEN,
 * for example after submission or when no draft exists.
 */
public class DraftNotEditableException extends RuntimeException {

    public DraftNotEditableException(UUID caseId, String detail) {
        super("Invoice case " + caseId + " has no editable draft: " + detail);
    }
}
