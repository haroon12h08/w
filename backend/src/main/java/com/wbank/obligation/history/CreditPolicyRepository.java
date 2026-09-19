package com.wbank.obligation.history;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditPolicyRepository extends JpaRepository<CreditPolicy, CreditPolicy.Key> {

    @Query("SELECT p FROM CreditPolicy p WHERE p.id.policyCode = :code AND p.effectiveFrom <= :at"
            + " ORDER BY p.effectiveFrom DESC, p.id.version DESC")
    List<CreditPolicy> findInForceAt(@Param("code") String code, @Param("at") Instant at);

    @Query("SELECT p FROM CreditPolicy p WHERE p.id.policyCode = :code ORDER BY p.id.version")
    List<CreditPolicy> findAllVersions(@Param("code") String code);
}
