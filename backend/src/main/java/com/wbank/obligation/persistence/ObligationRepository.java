package com.wbank.obligation.persistence;

import com.wbank.obligation.domain.Obligation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObligationRepository extends JpaRepository<Obligation, UUID> {

    /** Serialises every state change of one obligation (disbursement, repayments, cancel). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Obligation o WHERE o.id = :id")
    Optional<Obligation> findByIdForUpdate(@Param("id") UUID id);

    List<Obligation> findByDebtorPartyIdOrderByProposedAtAsc(UUID debtorPartyId);

    @Query("SELECT o.id FROM Obligation o WHERE o.status IN (com.wbank.obligation.domain.ObligationStatus.ACTIVE,"
            + " com.wbank.obligation.domain.ObligationStatus.DEFAULTED) ORDER BY o.proposedAt")
    List<UUID> findServicedIds();
}
