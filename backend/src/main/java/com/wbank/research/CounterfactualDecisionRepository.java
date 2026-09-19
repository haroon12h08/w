package com.wbank.research;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterfactualDecisionRepository extends JpaRepository<CounterfactualDecision, UUID> {
    List<CounterfactualDecision> findByReplayIdOrderByOriginalDecidedAtAscSourceDecisionIdAsc(UUID replayId);
}
