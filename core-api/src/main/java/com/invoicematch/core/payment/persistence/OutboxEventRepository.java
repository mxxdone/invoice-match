package com.invoicematch.core.payment.persistence;

import com.invoicematch.core.payment.domain.OutboxEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Insert/read path for outbox events. The relay's claim, lease and finalization
 * transitions use explicit claim-token compare-and-set statements in
 * {@code OutboxStore} rather than entity dirty checking.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    Optional<OutboxEvent> findByPaymentRequestId(UUID paymentRequestId);
}
