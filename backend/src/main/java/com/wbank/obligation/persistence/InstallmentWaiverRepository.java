package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.InstallmentWaiver;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallmentWaiverRepository extends JpaRepository<InstallmentWaiver, UUID> {

    List<InstallmentWaiver> findByObligationId(UUID obligationId);
}
