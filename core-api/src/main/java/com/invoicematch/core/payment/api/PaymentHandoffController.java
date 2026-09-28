package com.invoicematch.core.payment.api;

import com.invoicematch.core.payment.application.CaseHandoffStatus;
import com.invoicematch.core.payment.application.PaymentHandoffQueryService;
import com.invoicematch.core.security.AuthorizationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only ERP hand-off status for one case. The payment and outbox states are
 * returned exactly as stored. It is a case read: a SUBMITTER may read their own
 * case, and APPROVER/OPERATOR may read any case.
 */
@RestController
@RequestMapping("/api/invoice-cases")
public class PaymentHandoffController {

    private final PaymentHandoffQueryService handoffs;
    private final AuthorizationService authorization;

    public PaymentHandoffController(
            PaymentHandoffQueryService handoffs, AuthorizationService authorization) {
        this.handoffs = handoffs;
        this.authorization = authorization;
    }

    @GetMapping("/{id}/handoff")
    public CaseHandoffStatus handoff(@PathVariable UUID id) {
        authorization.requireCaseRead(id);
        return handoffs.handoff(id);
    }
}
