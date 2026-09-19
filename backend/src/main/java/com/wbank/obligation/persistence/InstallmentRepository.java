package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.Installment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallmentRepository extends JpaRepository<Installment, UUID> {

    List<Installment> findByObligationIdOrderBySequenceNoAsc(UUID obligationId);
}
