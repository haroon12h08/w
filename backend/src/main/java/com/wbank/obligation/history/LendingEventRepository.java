package com.wbank.obligation.history;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LendingEventRepository extends JpaRepository<LendingEvent, UUID> {

    List<LendingEvent> findByObligationIdOrderByLoanSeqAsc(UUID obligationId);

    @Query("SELECT COALESCE(MAX(e.loanSeq), 0) FROM LendingEvent e WHERE e.obligationId = :id")
    int maxLoanSeq(@Param("id") UUID obligationId);

    Optional<LendingEvent> findBySourceTableAndSourceId(String sourceTable, UUID sourceId);

    @Query("SELECT e FROM LendingEvent e WHERE e.eventType IN :types ORDER BY e.effectiveAt, e.globalSeq")
    List<LendingEvent> findByTypes(@Param("types") List<LendingEventType> types);
}
