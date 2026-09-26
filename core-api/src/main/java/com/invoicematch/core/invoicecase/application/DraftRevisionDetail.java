package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.invoicecase.domain.DraftRevision;
import java.util.UUID;

/**
 * Current editable draft revision as exposed over the API.
 */
public record DraftRevisionDetail(UUID id, int revisionNumber, String status) {

    public static DraftRevisionDetail from(DraftRevision revision) {
        return new DraftRevisionDetail(revision.id(), revision.revisionNumber(), revision.status().name());
    }
}
