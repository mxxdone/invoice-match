package com.invoicematch.core.matching.persistence;

import com.invoicematch.core.matching.domain.MatchResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchResultRepository extends JpaRepository<MatchResult, UUID> {
}
