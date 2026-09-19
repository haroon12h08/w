package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.LoanTerms;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanTermsRepository extends JpaRepository<LoanTerms, UUID> {
}
