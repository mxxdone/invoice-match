package com.invoicematch.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseId;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import com.invoicematch.core.support.TestActors;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The P1-07 approval seam takes the authoritative case object, so ownership can
 * never come from caller input. Self-approval is rejected even when the same
 * username also holds APPROVER.
 */
class AuthorizationServiceTest {

    private final AuthorizationService authorization =
            new AuthorizationService(new CurrentActorProvider(), mock(InvoiceCaseRepository.class));

    @AfterEach
    void clear() {
        TestActors.clear();
    }

    @Test
    void approvalSeamRejectsSelfApprovalEvenWithTheApproverRole() {
        InvoiceCase lockedCase = caseSubmittedBy("dana");
        TestActors.as("dana", "APPROVER");

        assertThatThrownBy(() -> authorization.requireApproverNotSubmitter(lockedCase))
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("may not approve");
    }

    @Test
    void approvalSeamAllowsAnotherApprover() {
        InvoiceCase lockedCase = caseSubmittedBy("dana");
        TestActors.as("frank", "APPROVER");

        assertThatCode(() -> authorization.requireApproverNotSubmitter(lockedCase))
                .doesNotThrowAnyException();
    }

    @Test
    void approvalSeamRejectsANonApprover() {
        InvoiceCase lockedCase = caseSubmittedBy("dana");
        TestActors.as("gina", "SUBMITTER");

        assertThatThrownBy(() -> authorization.requireApproverNotSubmitter(lockedCase))
                .isInstanceOf(ForbiddenActionException.class);
    }

    private static InvoiceCase caseSubmittedBy(String username) {
        return InvoiceCase.create(
                InvoiceCaseId.newId(),
                SupplierId.of("SUP-1"),
                PurchaseOrderId.of("PO-1"),
                "INV-1",
                "INV1",
                username,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
