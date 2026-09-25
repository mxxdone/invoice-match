package com.invoicematch.core.invoicecase.domain;

/**
 * State of a {@link DraftRevision}. A revision is editable while {@code OPEN};
 * once the claim is submitted (later ticket) it is {@code SEALED} and its
 * content is preserved through an immutable {@code EvidenceBundle} version.
 */
public enum DraftRevisionStatus {
    OPEN,
    SEALED
}
