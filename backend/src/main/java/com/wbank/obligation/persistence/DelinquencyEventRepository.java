package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.DelinquencyEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelinquencyEventRepository extends JpaRepository<DelinquencyEvent, UUID> {

    List<DelinquencyEvent> findByObligationIdOrderByRecordedAtAsc(UUID obligationId);
}
