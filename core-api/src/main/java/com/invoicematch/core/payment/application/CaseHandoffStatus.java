package com.invoicematch.core.payment.application;

import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import java.util.UUID;

/**
 * Hand-off status of a case. {@code payment} is null until the case has been
 * approved, so the UI can render "not handed off yet" without treating a
 * missing payment as an error. The case status and version are included so the
 * read is a complete point-in-time snapshot.
 */
public record CaseHandoffStatus(
        UUID invoiceCaseId,
        InvoiceCaseStatus caseStatus,
        long caseVersion,
        PaymentHandoffView payment) {
}
