package com.wbank.ledger.persistence;

import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountPurpose;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByCode(String code);

    List<LedgerAccount> findByPurposeAndCurrencyCode(LedgerAccountPurpose purpose, String currencyCode);

    boolean existsByCode(String code);
}
