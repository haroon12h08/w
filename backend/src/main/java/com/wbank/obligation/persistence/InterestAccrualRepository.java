package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.InterestAccrual;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, UUID> {

    List<InterestAccrual> findByObligationId(UUID obligationId);
}
