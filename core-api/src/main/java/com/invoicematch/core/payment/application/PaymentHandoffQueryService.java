package com.invoicematch.core.payment.application;

import com.invoicematch.core.approval.domain.PaymentRequest;
import com.invoicematch.core.approval.persistence.PaymentRequestRepository;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.payment.domain.OutboxEvent;
import com.invoicematch.core.payment.persistence.OutboxEventRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the ERP hand-off. It joins the immutable {@code PaymentRequest}
 * created by approval with its single {@code OutboxEvent} delivery row using
 * point lookups, never a list scan. A case that has not been approved yet simply
 * has no payment, which is returned as a null {@code payment} rather than a 404.
 */
@Service
@Transactional(readOnly = true)
public class PaymentHandoffQueryService {

    private final InvoiceCaseRepository invoiceCases;
    private final PaymentRequestRepository paymentRequests;
    private final OutboxEventRepository outboxEvents;

    public PaymentHandoffQueryService(
            InvoiceCaseRepository invoiceCases,
            PaymentRequestRepository paymentRequests,
            OutboxEventRepository outboxEvents) {
        this.invoiceCases = invoiceCases;
        this.paymentRequests = paymentRequests;
        this.outboxEvents = outboxEvents;
    }

    public CaseHandoffStatus handoff(UUID caseId) {
        InvoiceCase invoiceCase =
                invoiceCases.findById(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
        Optional<PaymentRequest> payment = paymentRequests.findByInvoiceCaseId(caseId);
        if (payment.isEmpty()) {
            return new CaseHandoffStatus(caseId, invoiceCase.status(), invoiceCase.version(), null);
        }
        OutboxEvent outbox =
                outboxEvents.findByPaymentRequestId(payment.get().id()).orElse(null);
        return new CaseHandoffStatus(
                caseId,
                invoiceCase.status(),
                invoiceCase.version(),
                PaymentHandoffView.from(payment.get(), outbox));
    }
}
