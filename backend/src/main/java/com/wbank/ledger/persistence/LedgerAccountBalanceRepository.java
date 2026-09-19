package com.wbank.ledger.persistence;

import com.wbank.ledger.domain.LedgerAccountBalance;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerAccountBalanceRepository extends JpaRepository<LedgerAccountBalance, UUID> {

    /**
     * Acquires an exclusive row lock on a single account's balance.
     *
     * <p>Locking one account at a time (callers lock in a globally deterministic order)
     * is what makes concurrent transfers both correct and deadlock-free. Optimistic
     * locking alone would be wrong here: we must read the balance, decide whether the
     * debit is permitted, and write, with no window in between.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM LedgerAccountBalance b WHERE b.ledgerAccountId = :id")
    Optional<LedgerAccountBalance> findByIdForUpdate(@Param("id") UUID id);
}
