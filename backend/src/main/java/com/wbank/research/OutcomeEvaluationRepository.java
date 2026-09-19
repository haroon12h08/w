package com.wbank.research;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutcomeEvaluationRepository extends JpaRepository<OutcomeEvaluation, UUID> {
    List<OutcomeEvaluation> findByReplayId(UUID replayId);
}
