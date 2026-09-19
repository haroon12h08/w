package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.Repayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepaymentRepository extends JpaRepository<Repayment, UUID> {

    Optional<Repayment> findByIdempotencyKey(String idempotencyKey);

    List<Repayment> findByObligationIdOrderByReceivedAtAsc(UUID obligationId);
}
