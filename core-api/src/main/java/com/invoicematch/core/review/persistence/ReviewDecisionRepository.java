package com.invoicematch.core.review.persistence;

import com.invoicematch.core.review.domain.ReviewDecision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {
}
