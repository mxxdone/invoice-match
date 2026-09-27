package com.invoicematch.core.security;

import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Context-aware authorization entry point. It resolves the current
 * {@link Actor} and applies {@link AuthorizationPolicy}, loading the case only
 * to read its authoritative {@code submittedBy}.
 *
 * <p>Controllers call this before any service so a denied request never enters
 * a business transaction. Write flows additionally compare the actor with the
 * case submitter, which is immutable, so there is no time-of-check window.
 */
@Component
public class AuthorizationService {

    private final CurrentActorProvider actors;
    private final InvoiceCaseRepository invoiceCases;

    public AuthorizationService(CurrentActorProvider actors, InvoiceCaseRepository invoiceCases) {
        this.actors = actors;
        this.invoiceCases = invoiceCases;
    }

    public Actor actor() {
        return actors.current();
    }

    public void requireRole(Role... roles) {
        AuthorizationPolicy.requireAnyRole(actor(), roles);
    }

    public void requireSubmitterOwner(UUID caseId) {
        InvoiceCase invoiceCase = load(caseId);
        AuthorizationPolicy.requireSubmitterOwner(actor(), caseId, invoiceCase.submittedBy());
    }

    /**
     * Transaction-bound ownership check. The caller has already loaded the
     * authoritative (locked) case, so the {@code submittedBy} it authorizes
     * against is the committed row, not a caller-supplied string.
     */
    public void requireSubmitterOwner(InvoiceCase lockedCase) {
        AuthorizationPolicy.requireSubmitterOwner(
                actor(), lockedCase.id().value(), lockedCase.submittedBy());
    }

    public void requireCaseRead(UUID caseId) {
        InvoiceCase invoiceCase = load(caseId);
        AuthorizationPolicy.requireCaseRead(actor(), caseId, invoiceCase.submittedBy());
    }

    /** Confirms the case exists, mapping a missing case to 404 before any leak. */
    public void requireCaseExists(UUID caseId) {
        load(caseId);
    }

    /**
     * P1-07 seam: requires the current actor to be an APPROVER and not the case
     * submitter. It takes the authoritative case (typically the row locked by the
     * approval transaction) so ownership can never come from request input.
     * P1-06 does not expose an approval endpoint.
     */
    public void requireApproverNotSubmitter(InvoiceCase lockedCase) {
        AuthorizationPolicy.requireApproverNotSubmitter(actor(), lockedCase.submittedBy());
    }

    private InvoiceCase load(UUID caseId) {
        return invoiceCases.findById(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
    }
}
