package com.wbank.account.funds;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FundsReservationRepository extends JpaRepository<FundsReservation, UUID> {

    @Query("SELECT COALESCE(SUM(r.amountMinor), 0) FROM FundsReservation r"
            + " WHERE r.ledgerAccountId = :ledgerAccountId AND r.status = com.wbank.account.funds.ReservationStatus.ACTIVE")
    long activeTotal(@Param("ledgerAccountId") UUID ledgerAccountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM FundsReservation r WHERE r.id = :id")
    Optional<FundsReservation> findByIdForUpdate(@Param("id") UUID id);
}
