package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.RepaymentAllocation;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepaymentAllocationRepository extends JpaRepository<RepaymentAllocation, RepaymentAllocation.Key> {

    /** Rows of [installmentId, principalPaid, interestPaid]: what has actually been paid, derived. */
    @Query("""
            SELECT a.id.installmentId, SUM(a.principalMinor), SUM(a.interestMinor)
              FROM RepaymentAllocation a
             WHERE a.id.installmentId IN :installmentIds
             GROUP BY a.id.installmentId""")
    List<Object[]> paidByInstallment(@Param("installmentIds") Collection<java.util.UUID> installmentIds);
}
