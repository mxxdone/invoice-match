package com.invoicematch.core.review.persistence;

import com.invoicematch.core.review.domain.ReviewSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewSnapshotRepository extends JpaRepository<ReviewSnapshot, UUID> {
}
