package com.wbank.obligation.history;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditDecisionSnapshotRepository extends JpaRepository<CreditDecisionSnapshot, UUID> {

    Optional<CreditDecisionSnapshot> findByDecisionId(UUID decisionId);
}
