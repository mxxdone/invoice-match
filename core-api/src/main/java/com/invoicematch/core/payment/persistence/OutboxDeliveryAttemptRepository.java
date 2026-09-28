package com.invoicematch.core.payment.persistence;

import com.invoicematch.core.payment.domain.OutboxDeliveryAttempt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxDeliveryAttemptRepository extends JpaRepository<OutboxDeliveryAttempt, UUID> {
}
