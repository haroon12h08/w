package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.LoanDecision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanDecisionRepository extends JpaRepository<LoanDecision, UUID> {

    List<LoanDecision> findByObligationIdOrderByDecidedAtAsc(UUID obligationId);
}
