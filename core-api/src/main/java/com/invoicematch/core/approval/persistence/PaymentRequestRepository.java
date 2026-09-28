package com.invoicematch.core.approval.persistence;

import com.invoicematch.core.approval.domain.PaymentRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {

    Optional<PaymentRequest> findByExternalRequestKey(String externalRequestKey);

    Optional<PaymentRequest> findByInvoiceCaseIdAndReviewSnapshotId(UUID invoiceCaseId, UUID reviewSnapshotId);

    Optional<PaymentRequest> findByInvoiceCaseId(UUID invoiceCaseId);
}
